/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
** Modified to support SQLite extensions by the SQLite developers: 
** sqlite-dev@sqlite.org.
*/

package org.sqlite.database.sqlite;

/* import dalvik.system.BlockGuard; */
import android.content.ContentValues;
import android.text.TextUtils;
import android.util.Log;
import org.opendatakit.common.android.database.AppNameSharedStateContainer;
import org.opendatakit.common.android.database.OperationLog;
import org.opendatakit.common.android.utilities.ODKFileUtils;
import org.opendatakit.common.android.utilities.WebLogger;

import android.database.Cursor;
import android.database.CursorWindow;
import android.database.DatabaseUtils;
import org.sqlite.database.DatabaseErrorHandler;
import org.sqlite.database.DefaultDatabaseErrorHandler;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import org.sqlite.database.SQLException;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Represents a SQLite database connection.
 * Each connection wraps an instance of a native <code>sqlite3</code> object.
 * <p>
 * When database connection pooling is enabled, there can be multiple active
 * connections to the same database.  Otherwise there is typically only one
 * connection per database.
 * </p><p>
 * When the SQLite WAL feature is enabled, multiple readers and one writer
 * can concurrently access the database.  Without WAL, readers and writers
 * are mutually exclusive.
 * </p>
 *
 * <h2>Ownership and concurrency guarantees</h2>
 * <p>
 * Connection objects are not thread-safe. Classes using connections are
 * responsible for serializing operations to guard against concurrent
 * use of a connection.
 * </p><p>
 * The guarantee of having a single owner allows this class to be implemented
 * without locks and greatly simplifies resource management.
 * </p>
 *
 * <h2>Encapsulation guarantees</h2>
 * <p>
 * The connection object object owns *all* of the SQLite related native
 * objects that are associated with the connection.  What's more, there are
 * no other objects in the system that are capable of obtaining handles to
 * those native objects.  Consequently, when the connection is closed, we do
 * not have to worry about what other components might have references to
 * its associated SQLite state -- there are none.
 * </p><p>
 * Encapsulation is what ensures that the connection object's
 * lifecycle does not become a tortured mess of finalizers and reference
 * queues.
 * </p>
 *
 * <h2>Reentrance</h2>
 * <p>
 * This class must tolerate reentrant execution of SQLite operations because
 * triggers may call custom SQLite functions that perform additional queries.
 * </p>
 *
 * @hide
 */
public final class SQLiteConnection extends SQLiteClosable implements CancellationSignal.OnCancelListener {
    private static final String TAG = "SQLiteConnection";
    private static final boolean DEBUG = false;

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

   /**
    * A callback interface for a custom sqlite3 function.
    * This can be used to create a function that can be called from
    * sqlite3 database triggers.
    * @hide
    */
   public interface CustomFunction {
      public void callback(String[] args);
   }

   /**
    * Holder type for a prepared statement.
    *
    * Although this object holds a pointer to a native statement object, it
    * does not have a finalizer.  This is deliberate.  The {@link SQLiteConnection}
    * owns the statement object and will take care of freeing it when needed.
    * In particular, closing the connection requires a guarantee of deterministic
    * resource disposal because all native statement objects must be freed before
    * the native database object can be closed.  So no finalizers here.
    */
   private static final class PreparedStatement {
      // Next item in pool.
      public PreparedStatement mPoolNext;

      // The SQL from which the statement was prepared.
      public String mSql;

      // The native sqlite3_stmt object pointer.
      // Lifetime is managed explicitly by the connection.
      public long mStatementPtr;

      // The number of parameters that the prepared statement has.
      public int mNumParameters;

      // The statement type.
      public int mType;

      // True if the statement is read-only.
      public boolean mReadOnly;

      // True if the statement is in use (currently executing).
      // We need this flag because due to the use of custom functions in triggers, it's
      // possible for SQLite calls to be re-entrant.  Consequently we need to prevent
      // in use statements from being finalized until they are no longer in use.
      public boolean mInUse;
   }


   /**
    * Transaction mode: Deferred.
    * <p>
    * In a deferred transaction, no locks are acquired on the database
    * until the first operation is performed.  If the first operation is
    * read-only, then a <code>SHARED</code> lock is acquired, otherwise
    * a <code>RESERVED</code> lock is acquired.
    * </p><p>
    * While holding a <code>SHARED</code> lock, this session is only allowed to
    * read but other sessions are allowed to read or write.
    * While holding a <code>RESERVED</code> lock, this session is allowed to read
    * or write but other sessions are only allowed to read.
    * </p><p>
    * Because the lock is only acquired when needed in a deferred transaction,
    * it is possible for another session to write to the database first before
    * this session has a chance to do anything.
    * </p><p>
    * Corresponds to the SQLite <code>BEGIN DEFERRED</code> transaction mode.
    * </p>
    */
   public static final int TRANSACTION_MODE_DEFERRED = 0;

   /**
    * Transaction mode: Immediate.
    * <p>
    * When an immediate transaction begins, the session acquires a
    * <code>RESERVED</code> lock.
    * </p><p>
    * While holding a <code>RESERVED</code> lock, this session is allowed to read
    * or write but other sessions are only allowed to read.
    * </p><p>
    * Corresponds to the SQLite <code>BEGIN IMMEDIATE</code> transaction mode.
    * </p>
    */
   public static final int TRANSACTION_MODE_IMMEDIATE = 1;

   /**
    * Transaction mode: Exclusive.
    * <p>
    * When an exclusive transaction begins, the session acquires an
    * <code>EXCLUSIVE</code> lock.
    * </p><p>
    * While holding an <code>EXCLUSIVE</code> lock, this session is allowed to read
    * or write but no other sessions are allowed to access the database.
    * </p><p>
    * Corresponds to the SQLite <code>BEGIN EXCLUSIVE</code> transaction mode.
    * </p>
    */
   public static final int TRANSACTION_MODE_EXCLUSIVE = 2;

   /**
    * When a constraint violation occurs, an immediate ROLLBACK occurs,
    * thus ending the current transaction, and the command aborts with a
    * return code of SQLITE_CONSTRAINT. If no transaction is active
    * (other than the implied transaction that is created on every command)
    * then this algorithm works the same as ABORT.
    */
   public static final int CONFLICT_ROLLBACK = 1;

   /**
    * When a constraint violation occurs,no ROLLBACK is executed
    * so changes from prior commands within the same transaction
    * are preserved. This is the default behavior.
    */
   public static final int CONFLICT_ABORT = 2;

   /**
    * When a constraint violation occurs, the command aborts with a return
    * code SQLITE_CONSTRAINT. But any changes to the database that
    * the command made prior to encountering the constraint violation
    * are preserved and are not backed out.
    */
   public static final int CONFLICT_FAIL = 3;

   /**
    * When a constraint violation occurs, the one row that contains
    * the constraint violation is not inserted or changed.
    * But the command continues executing normally. Other rows before and
    * after the row that contained the constraint violation continue to be
    * inserted or updated normally. No error is returned.
    */
   public static final int CONFLICT_IGNORE = 4;

   /**
    * When a UNIQUE constraint violation occurs, the pre-existing rows that
    * are causing the constraint violation are removed prior to inserting
    * or updating the current row. Thus the insert or update always occurs.
    * The command continues executing normally. No error is returned.
    * If a NOT NULL constraint violation occurs, the NULL value is replaced
    * by the default value for that column. If the column has no default
    * value, then the ABORT algorithm is used. If a CHECK constraint
    * violation occurs then the IGNORE algorithm is used. When this conflict
    * resolution strategy deletes rows in order to satisfy a constraint,
    * it does not invoke delete triggers on those rows.
    * This behavior might change in a future release.
    */
   public static final int CONFLICT_REPLACE = 5;

   /**
    * Use the following when no conflict action is specified.
    */
   public static final int CONFLICT_NONE = 0;

   private static final String[] CONFLICT_VALUES = new String[]
       {"", " OR ROLLBACK ", " OR ABORT ", " OR FAIL ", " OR IGNORE ", " OR REPLACE "};

   /**
    * Maximum Length Of A LIKE Or GLOB Pattern
    * The pattern matching algorithm used in the default LIKE and GLOB implementation
    * of SQLite can exhibit O(N^2) performance (where N is the number of characters in
    * the pattern) for certain pathological cases. To avoid denial-of-service attacks
    * the length of the LIKE or GLOB pattern is limited to SQLITE_MAX_LIKE_PATTERN_LENGTH bytes.
    * The default value of this limit is 50000. A modern workstation can evaluate
    * even a pathological LIKE or GLOB pattern of 50000 bytes relatively quickly.
    * The denial of service problem only comes into play when the pattern length gets
    * into millions of bytes. Nevertheless, since most useful LIKE or GLOB patterns
    * are at most a few dozen bytes in length, paranoid application developers may
    * want to reduce this parameter to something in the range of a few hundred
    * if they know that external users are able to generate arbitrary patterns.
    */
   public static final int SQLITE_MAX_LIKE_PATTERN_LENGTH = 50000;

   /**
    * Open flag: Flag for {@link SQLiteDatabaseConfiguration} to open the database for reading and writing.
    * If the disk is full, this may fail even before you actually write anything.
    *
    * {@more} Note that the value of this flag is 0, so it is the default.
    */
   public static final int OPEN_READWRITE = 0x00000000;          // update native code if changing

   /**
    * Open flag: Flag for {@link #openDatabase} to open the database for reading only.
    * This is the only reliable way to open a database if the disk may be full.
    */
   // public static final int OPEN_READONLY = 0x00000001;           // update native code if changing

   // private static final int OPEN_READ_MASK = 0x00000001;         // update native code if changing

   /**
    * Open flag: Flag for {@link SQLiteDatabaseConfiguration} to open the database without support for
    * localized collators.
    *
    * {@more} This causes the collator <code>LOCALIZED</code> not to be created.
    * You must be consistent when using this flag to use the setting the database was
    * created with.  If this is set, setLocale will do nothing.
    */
   public static final int NO_LOCALIZED_COLLATORS = 0x00000010;  // update native code if changing

   /**
    * Open flag: Flag for {@link SQLiteDatabaseConfiguration} to create the database file if it does not
    * already exist.
    */
   public static final int CREATE_IF_NECESSARY = 0x10000000;     // update native code if changing

   /**
    * Open flag: Flag for {@link SQLiteDatabaseConfiguration} to open the database file with
    * write-ahead logging enabled by default.
    */
   public static final int ENABLE_WRITE_AHEAD_LOGGING = 0x20000000;

   /**
    * Pattern used to extract the limit condition from a query.
    * Pattern is thread-safe after it is compiled.
    */
   private static final Pattern sLimitPattern =
       Pattern.compile("\\s*\\d+\\s*(,\\s*\\d+\\s*)?");

   /*******************************************************************************************
    * JNI Class methods
    *******************************************************************************************/

   /**
    * Open the database, returning a pointer to the C++ object in the long.
    *
    * @param path
    * @param openFlags
    * @param label
    * @param enableTrace
    * @param enableProfile
    * @return pointer to underlying connection in C++ layer.
    */
   private static native long nativeOpen(String path, int openFlags, String label,
       boolean enableTrace, boolean enableProfile);
   private static native void nativeClose(long connectionPtr);
   private static native void nativeRegisterCustomFunction(long connectionPtr,
       SQLiteCustomFunction function);
   private static native void nativeRegisterLocalizedCollators(long connectionPtr, String locale);
   private static native long nativePrepareStatement(long connectionPtr, String sql);
   private static native void nativeFinalizeStatement(long connectionPtr, long statementPtr);
   private static native int nativeGetParameterCount(long connectionPtr, long statementPtr);
   private static native boolean nativeIsReadOnly(long connectionPtr, long statementPtr);
   private static native int nativeGetColumnCount(long connectionPtr, long statementPtr);
   private static native String nativeGetColumnName(long connectionPtr, long statementPtr,
       int index);
   private static native void nativeBindNull(long connectionPtr, long statementPtr,
       int index);
   private static native void nativeBindLong(long connectionPtr, long statementPtr,
       int index, long value);
   private static native void nativeBindDouble(long connectionPtr, long statementPtr,
       int index, double value);
   private static native void nativeBindString(long connectionPtr, long statementPtr,
       int index, String value);
   private static native void nativeBindBlob(long connectionPtr, long statementPtr,
       int index, byte[] value);
   private static native void nativeResetStatementAndClearBindings(
       long connectionPtr, long statementPtr);
   private static native void nativeExecute(long connectionPtr, long statementPtr);
   private static native long nativeExecuteForLong(long connectionPtr, long statementPtr);
   private static native String nativeExecuteForString(long connectionPtr, long statementPtr);
   private static native int nativeExecuteForBlobFileDescriptor(
       long connectionPtr, long statementPtr);
   private static native int nativeExecuteForChangedRowCount(long connectionPtr, long statementPtr);
   private static native long nativeExecuteForLastInsertedRowId(
       long connectionPtr, long statementPtr);
   private static native long nativeExecuteForCursorWindow(
       long connectionPtr, long statementPtr, CursorWindow win,
       int startPos, int requiredPos, boolean countAllRows);
   private static native int nativeGetDbLookaside(long connectionPtr);
   private static native void nativeCancel(long connectionPtr);
   private static native void nativeResetCancel(long connectionPtr, boolean cancelable);

   private static native boolean nativeHasCodec();


   /*******************************************************************************************
    * Class methods
    *******************************************************************************************/

   public static boolean hasCodec(){
      return nativeHasCodec();
   }

   private static String canonicalizeSyncMode(String value) {
      if (value.equals("0")) {
         return "OFF";
      } else if (value.equals("1")) {
         return "NORMAL";
      } else if (value.equals("2")) {
         return "FULL";
      }
      return value;
   }

   /**
    * Returns data type of the given object's value.
    *<p>
    * Returned values are
    * <ul>
    *   <li>{@link Cursor#FIELD_TYPE_NULL}</li>
    *   <li>{@link Cursor#FIELD_TYPE_INTEGER}</li>
    *   <li>{@link Cursor#FIELD_TYPE_FLOAT}</li>
    *   <li>{@link Cursor#FIELD_TYPE_STRING}</li>
    *   <li>{@link Cursor#FIELD_TYPE_BLOB}</li>
    *</ul>
    *</p>
    *
    * @param obj the object whose value type is to be returned
    * @return object value type
    */
   public static int getTypeOfObject(Object obj) {
      if (obj == null) {
         return Cursor.FIELD_TYPE_NULL;
      } else if (obj instanceof byte[]) {
         return Cursor.FIELD_TYPE_BLOB;
      } else if (obj instanceof Float || obj instanceof Double) {
         return Cursor.FIELD_TYPE_FLOAT;
      } else if (obj instanceof Long || obj instanceof Integer
          || obj instanceof Short || obj instanceof Byte) {
         return Cursor.FIELD_TYPE_INTEGER;
      } else {
         return Cursor.FIELD_TYPE_STRING;
      }
   }

   /**
    * Build an SQL query string from the given clauses.
    *
    * @param distinct true if you want each row to be unique, false otherwise.
    * @param tables The table names to compile the query against.
    * @param columns A list of which columns to return. Passing null will
    *            return all columns, which is discouraged to prevent reading
    *            data from storage that isn't going to be used.
    * @param where A filter declaring which rows to return, formatted as an SQL
    *            WHERE clause (excluding the WHERE itself). Passing null will
    *            return all rows for the given URL.
    * @param groupBy A filter declaring how to group rows, formatted as an SQL
    *            GROUP BY clause (excluding the GROUP BY itself). Passing null
    *            will cause the rows to not be grouped.
    * @param having A filter declare which row groups to include in the cursor,
    *            if row grouping is being used, formatted as an SQL HAVING
    *            clause (excluding the HAVING itself). Passing null will cause
    *            all row groups to be included, and is required when row
    *            grouping is not being used.
    * @param orderBy How to order the rows, formatted as an SQL ORDER BY clause
    *            (excluding the ORDER BY itself). Passing null will use the
    *            default sort order, which may be unordered.
    * @param limit Limits the number of rows returned by the query,
    *            formatted as LIMIT clause. Passing null denotes no LIMIT clause.
    * @return the SQL query string
    */
   private static String buildQueryString(
       boolean distinct, String tables, String[] columns, String where,
       String groupBy, String having, String orderBy, String limit) {
      if (TextUtils.isEmpty(groupBy) && !TextUtils.isEmpty(having)) {
         throw new IllegalArgumentException(
             "HAVING clauses are only permitted when using a groupBy clause");
      }
      if (!TextUtils.isEmpty(limit) && !sLimitPattern.matcher(limit).matches()) {
         throw new IllegalArgumentException("invalid LIMIT clauses:" + limit);
      }

      StringBuilder query = new StringBuilder(120);

      query.append("SELECT ");
      if (distinct) {
         query.append("DISTINCT ");
      }
      if (columns != null && columns.length != 0) {
         appendColumns(query, columns);
      } else {
         query.append("* ");
      }
      query.append("FROM ");
      query.append(tables);
      appendClause(query, " WHERE ", where);
      appendClause(query, " GROUP BY ", groupBy);
      appendClause(query, " HAVING ", having);
      appendClause(query, " ORDER BY ", orderBy);
      appendClause(query, " LIMIT ", limit);

      return query.toString();
   }

   private static void appendClause(StringBuilder s, String name, String clause) {
      if (!TextUtils.isEmpty(clause)) {
         s.append(name);
         s.append(clause);
      }
   }

   /**
    * Add the names that are non-null in columns to s, separating
    * them with commas.
    */
   private static void appendColumns(StringBuilder s, String[] columns) {
      int n = columns.length;

      for (int i = 0; i < n; i++) {
         String column = columns[i];

         if (column != null) {
            if (i > 0) {
               s.append(", ");
            }
            s.append(column);
         }
      }
      s.append(' ');
   }

   /*****************************************************************************************
    * Member variables
    * ***************************************************************************************/

   /**
    * Private copy of the database configuration (read-only).
    * This can be accessed outside of locks
    * Thread safe.
    */
    private final SQLiteDatabaseConfiguration mConfiguration;

   /**
    * Session qualifier supplied by user.
    * This can be accessed outside of locks
    * Thread safe.
    */
    private final String mSessionQualifier;

   /**
    * The operations log.
    * This can be accessed outside of locks
    * Thread safe.
    */
    private final OperationLog mRecentOperations;

   // Error handler to be used when SQLite returns corruption errors.
   // This can be accessed outside of locks
   private final DatabaseErrorHandler mErrorHandler;

   /**
    * Mutex used when accessing or using mConnectionPtr
    * i.e., when calling native methods that use it.
    */
   private final Object mConnectionPtrMutex = new Object();

   /**
    * Manage the nesting of transactions on this connection.
    *
    * <em>Should be accessed within the mConnectionPtrMutex lock</em>
    * Thread-safe.
    */
   private final SQLiteTransactionManager mTransactionManager;

   /**
    * The native (C++) SQLiteConnection pointer.
    *
    * <em>Should be accessed within the mConnectionPtrMutex lock</em>
    */
    private long mConnectionPtr = 0L;

   /**
    * The number of times attachCancellationSignal has been called.
    * Because SQLite statement execution can be reentrant, we keep track of how many
    * times we have attempted to attach a cancellation signal to the connection so that
    * we can ensure that we detach the signal at the right time.
    *
    * <em>Should be accessed within the mConnectionPtrMutex lock</em>
    */
    private int mCancellationSignalAttachCount;

   /**
    * Tracks whether this SQLiteConnection has been closed/released
    * before it was finalized.
    *
    * <em>Should be accessed within the mConnectionPtrMutex lock</em>
    */
   private String mAllocationReference;

   /**
    * Tracks all open cursors.
    *
    * <em>Should be accessed within the mConnectionPtrMutex lock</em>
    */
   private WeakHashMap<SQLiteCursor, Object> mActiveCursors = new
       WeakHashMap<SQLiteCursor, Object>();

   public SQLiteConnection(SQLiteDatabaseConfiguration configuration,
                             OperationLog recentOperations,
                             DatabaseErrorHandler errorHandler,
                             String sessionQualifier) {
        mConfiguration = new SQLiteDatabaseConfiguration(configuration);
        mRecentOperations = recentOperations;
        mErrorHandler = (errorHandler != null) ? errorHandler : new DefaultDatabaseErrorHandler();
        mSessionQualifier = sessionQualifier;
        mAllocationReference = mConfiguration.appName + " " + mSessionQualifier;
        mTransactionManager = new SQLiteTransactionManager();
        mPreparedStatementCache = new PreparedStatementCache(mConfiguration.maxSqlCacheSize);
    }

    public WebLogger getLogger() {
      return WebLogger.getLogger(mConfiguration.appName);
    }

   public String getAppName() {
      return mConfiguration.appName;
   }

   public String getSessionQualifier() {
      return mSessionQualifier;
   }

   public String getPath() {
      return mConfiguration.path;
   }

   ////////////////////////////////////////////////////////////////////////
   /// FROM SQLiteDatabase

   @Override
   protected synchronized void finalize() throws Throwable {
      boolean refCountBelowZero = getReferenceCount() < 0;
      try {
         boolean isNotClosed = isOpen();
         if (isNotClosed) {
            // This code is running inside of the SQLiteConnection finalizer.
            //
            // We don't know whether it is just the connection that has been finalized (and leaked)
            // or whether the connection pool has also been or is about to be finalized.
            // Consequently, it would be a bad idea to try to grab any locks or to
            // do any significant work here.  So we do the simplest possible thing and
            // set a flag.  waitForConnection() periodically checks this flag (when it
            // times out) so that it can recover from leaked connections and wake
            // itself or other threads up if necessary.
            //
            // You might still wonder why we don't try to do more to wake up the waiters
            // immediately.  First, as explained above, it would be hard to do safely
            // unless we started an extra Thread to function as a reference queue.  Second,
            // this is never supposed to happen in normal operation.  Third, there is no
            // guarantee that the GC will actually detect the leak in a timely manner so
            // it's not all that important that we recover from the leak in a timely manner
            // either.  Fourth, if a badly behaved application finds itself hung waiting for
            // several seconds while waiting for a leaked connection to be detected and recreated,
            // then perhaps its authors will have added incentive to fix the problem!

            getLogger().e(TAG, "A SQLiteConnection object for database '" + mConfiguration.appName
                + "' sessionQualifier '" + mSessionQualifier + "' was leaked!  Please fix your "
                + "application to end transactions in progress properly and to close the "
                + "database when it is no longer needed.");
         }

         dispose(true, refCountBelowZero);
      } finally {
         super.finalize();
      }
   }

   @Override
   protected void onAllReferencesReleased(boolean refCountBelowZero) {
      dispose(false, refCountBelowZero);
   }

   private void dispose(boolean finalized, boolean refCountBelowZero) {
      String context = (finalized) ? "finalize: " : "onAllReferencesReleased: ";

      if ( refCountBelowZero ) {
         getLogger().e(TAG, "A SQLiteConnection object for database '" + mConfiguration.appName
             + "' sessionQualifier '" + mSessionQualifier + "' has a negative "
             + "referenceCount! (logic error)");
      }

      try {
         File f = new File(ODKFileUtils.getWebDbFolder(getAppName()));
         if ( f.exists() && f.isDirectory() ) {
            closeImpl(finalized);
            getLogger().i(TAG,
                "A SQLiteConnection object for database '" + mConfiguration.appName
                    + "' sessionQualifier '"
                    + mSessionQualifier + "' successfully closed.");
         } else {
            if ( finalized ) {
               // AndroidUnitTest might clear up directory before resources are GC'd
               Log.w(TAG, "Database directory for database '" + mConfiguration.appName
                   + "' sessionQualifier '" + mSessionQualifier + "' does not exist");
            } else {
               // getting here is wrong!
               Log.e(TAG, "Database directory for database '" + mConfiguration.appName
                   + "' sessionQualifier '" + mSessionQualifier + "' does not exist");
            }
         }
      } catch (Throwable t) {
         getLogger().e(TAG,
             "A SQLiteConnection object for database '" + mConfiguration.appName
                 + "' sessionQualifier '" + mSessionQualifier + "' exception during close().");
         getLogger().printStackTrace(t);
         throw t;
      }
   }

   public void open() {
      try {
         openImpl();
      } catch (SQLiteDatabaseCorruptException ex) {
         onCorruption();
      }
   }

   /**
    * Runs 'pragma integrity_check' on the given database (and all the attached databases)
    * and returns true if the given database (and all its attached databases) pass integrity_check,
    * false otherwise.
    *<p>
    * If the result is false, then this method logs the errors reported by the integrity_check
    * command execution.
    *<p>
    * Note that 'pragma integrity_check' on a database can take a long time.
    *
    * @return true if the given database (and all its attached databases) pass integrity_check,
    * false otherwise.
    */
   public boolean isDatabaseIntegrityOk() {
      String rslt = null;
      rslt = executeForStringImpl("PRAGMA main.integrity_check(1);", null, null);

      if (!rslt.equalsIgnoreCase("ok")) {
         // integrity_checker failed on main database
         getLogger().e(TAG, mConfiguration.appName + " " + this.mSessionQualifier +
             " PRAGMA main.integrity_check(1); returned: " + rslt);
         return false;
      }
      return true;
   }

   /**
    * Sends a corruption message to the database error handler.
    */
   void onCorruption() {
      mErrorHandler.onCorruption(this);
   }

   /**
    * Executes a statement that does not return a result.
    *
    * @param sql The SQL statement to execute.
    * @param bindArgs The arguments to bind, or null if none.
    * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
    *
    * @throws SQLiteException if an error occurs, such as a syntax error
    * or invalid number of bind arguments.
    * @throws OperationCanceledException if the operation was canceled.
    */
   public void execute(String sql, Object[] bindArgs,
       CancellationSignal cancellationSignal) {
      if (sql == null) {
         throw new IllegalArgumentException("sql must not be null.");
      }

      if (executeSpecial(sql, bindArgs, cancellationSignal)) {
         return;
      }

      executeImpl(sql, bindArgs, cancellationSignal); // might throw
   }

   /**
    * Executes a statement that returns a single <code>long</code> result.
    *
    * @param sql The SQL statement to execute.
    * @param bindArgs The arguments to bind, or null if none.
    * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
    * @return The value of the first column in the first row of the result set
    * as a <code>long</code>, or zero if none.
    *
    * @throws SQLiteException if an error occurs, such as a syntax error
    * or invalid number of bind arguments.
    * @throws OperationCanceledException if the operation was canceled.
    */
   public long executeForLong(String sql, Object[] bindArgs,
       CancellationSignal cancellationSignal) {
      if (sql == null) {
         throw new IllegalArgumentException("sql must not be null.");
      }

      if (executeSpecial(sql, bindArgs, cancellationSignal)) {
         return 0L;
      }

      return executeForLongImpl(sql, bindArgs, cancellationSignal); // might throw
   }

   /**
    * Executes a statement that returns a single {@link String} result.
    *
    * @param sql The SQL statement to execute.
    * @param bindArgs The arguments to bind, or null if none.
    * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
    * @return The value of the first column in the first row of the result set
    * as a <code>String</code>, or null if none.
    *
    * @throws SQLiteException if an error occurs, such as a syntax error
    * or invalid number of bind arguments.
    * @throws OperationCanceledException if the operation was canceled.
    */
   public String executeForString(String sql, Object[] bindArgs,
       CancellationSignal cancellationSignal) {
      if (sql == null) {
         throw new IllegalArgumentException("sql must not be null.");
      }

      if (executeSpecial(sql, bindArgs, cancellationSignal)) {
         return null;
      }

      return executeForStringImpl(sql, bindArgs, cancellationSignal); // might throw
   }

   /**
    * Executes a statement that returns a single BLOB result as a
    * file descriptor to a shared memory region.
    *
    * @param sql The SQL statement to execute.
    * @param bindArgs The arguments to bind, or null if none.
    * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
    * @return The file descriptor for a shared memory region that contains
    * the value of the first column in the first row of the result set as a BLOB,
    * or null if none.
    *
    * @throws SQLiteException if an error occurs, such as a syntax error
    * or invalid number of bind arguments.
    * @throws OperationCanceledException if the operation was canceled.
    */
   public ParcelFileDescriptor executeForBlobFileDescriptor(String sql, Object[] bindArgs,
       CancellationSignal cancellationSignal) {
      if (sql == null) {
         throw new IllegalArgumentException("sql must not be null.");
      }

      if (executeSpecial(sql, bindArgs, cancellationSignal)) {
         return null;
      }

      return executeForBlobFileDescriptorImpl(sql, bindArgs, cancellationSignal); // might throw
   }

   /**
    * Executes a statement that returns a count of the number of rows
    * that were changed.  Use for UPDATE or DELETE SQL statements.
    *
    * @param sql The SQL statement to execute.
    * @param bindArgs The arguments to bind, or null if none.
    * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
    * @return The number of rows that were changed.
    *
    * @throws SQLiteException if an error occurs, such as a syntax error
    * or invalid number of bind arguments.
    * @throws OperationCanceledException if the operation was canceled.
    */
   public int executeForChangedRowCount(String sql, Object[] bindArgs,
       CancellationSignal cancellationSignal) {
      if (sql == null) {
         throw new IllegalArgumentException("sql must not be null.");
      }

      if (executeSpecial(sql, bindArgs, cancellationSignal)) {
         return 0;
      }

      return executeForChangedRowCountImpl(sql, bindArgs, cancellationSignal); // might throw
   }

   /**
    * Executes a statement that returns the row id of the last row inserted
    * by the statement.  Use for INSERT SQL statements.
    *
    * @param sql The SQL statement to execute.
    * @param bindArgs The arguments to bind, or null if none.
    * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
    * @return The row id of the last row that was inserted, or 0 if none.
    *
    * @throws SQLiteException if an error occurs, such as a syntax error
    * or invalid number of bind arguments.
    * @throws OperationCanceledException if the operation was canceled.
    */
   public long executeForLastInsertedRowId(String sql, Object[] bindArgs,
       CancellationSignal cancellationSignal) {
      if (sql == null) {
         throw new IllegalArgumentException("sql must not be null.");
      }

      if (executeSpecial(sql, bindArgs, cancellationSignal)) {
         return 0;
      }

      return executeForLastInsertedRowIdImpl(sql, bindArgs, cancellationSignal); // might throw
   }

   /**
    * Executes a statement and populates the specified {@link CursorWindow}
    * with a range of results.  Returns the number of rows that were counted
    * during query execution.
    *
    * @param sql The SQL statement to execute.
    * @param bindArgs The arguments to bind, or null if none.
    * @param window The cursor window to clear and fill.
    * @param startPos The start position for filling the window.
    * @param requiredPos The position of a row that MUST be in the window.
    * If it won't fit, then the query should discard part of what it filled
    * so that it does.  Must be greater than or equal to <code>startPos</code>.
    * @param countAllRows True to count all rows that the query would return
    * regagless of whether they fit in the window.
    * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
    * @return The number of rows that were counted during query execution.  Might
    * not be all rows in the result set unless <code>countAllRows</code> is true.
    *
    * @throws SQLiteException if an error occurs, such as a syntax error
    * or invalid number of bind arguments.
    * @throws OperationCanceledException if the operation was canceled.
    */
   int executeForCursorWindow(String sql, Object[] bindArgs,
       CursorWindow window, int startPos, int requiredPos, boolean countAllRows,
       CancellationSignal cancellationSignal) {
      if (sql == null) {
         throw new IllegalArgumentException("sql must not be null.");
      }
      if (window == null) {
         throw new IllegalArgumentException("window must not be null.");
      }

      if (executeSpecial(sql, bindArgs, cancellationSignal)) {
         window.clear();
         return 0;
      }

      return executeForCursorWindowImpl(sql, bindArgs, window, startPos, requiredPos,
          countAllRows, cancellationSignal); // might throw
   }

   /**
    * Performs special reinterpretation of certain SQL statements such as "BEGIN",
    * "COMMIT" and "ROLLBACK" to ensure that transaction state invariants are
    * maintained.
    *
    * This function is mainly used to support legacy apps that perform their
    * own transactions by executing raw SQL rather than calling {@link #beginTransactionNonExclusive}
    * and the like.
    *
    * @param sql The SQL statement to execute.
    * @param bindArgs The arguments to bind, or null if none.
    * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
    * @return True if the statement was of a special form that was handled here,
    * false otherwise.
    *
    * @throws SQLiteException if an error occurs, such as a syntax error
    * or invalid number of bind arguments.
    * @throws OperationCanceledException if the operation was canceled.
    */
   private boolean executeSpecial(String sql, Object[] bindArgs,
       CancellationSignal cancellationSignal) {
      if (sql == null) {
         throw new IllegalArgumentException("sql must not be null.");
      }

      if (cancellationSignal != null) {
         cancellationSignal.throwIfCanceled();
      }

      final int type = DatabaseUtils.getSqlStatementType(sql);
      switch (type) {
      case DatabaseUtils.STATEMENT_BEGIN:
            beginTransactionNonExclusive(cancellationSignal);
         return true;

      case DatabaseUtils.STATEMENT_COMMIT:
         commitTransactionImpl(cancellationSignal);
         return true;

      case DatabaseUtils.STATEMENT_ABORT:
         endTransaction(cancellationSignal);
         return true;
      }
      return false;
   }

   /**
    * Begins a transaction in IMMEDIATE mode. Transactions can be nested. When
    * the outer transaction is ended all of the work done in that transaction
    * and all of the nested transactions will be committed or rolled back. The
    * changes will be rolled back if any transaction is ended without being
    * marked as clean (by calling setTransactionSuccessful). Otherwise they
    * will be committed.
    * <p>
    * Here is the standard idiom for transactions:
    *
    * <pre>
    *   db.beginTransactionNonExclusive();
    *   try {
    *     ...
    *     db.setTransactionSuccessful();
    *   } finally {
    *     db.endTransaction();
    *   }
    * </pre>
    */
   public void beginTransactionNonExclusive() {
      // TODO: change this to Immediate?
      beginTransactionImpl(TRANSACTION_MODE_DEFERRED, null);
   }

   public void beginTransactionNonExclusive(CancellationSignal cancellationSignal) {
      // TODO: change this to Immediate?
      beginTransactionImpl(TRANSACTION_MODE_DEFERRED, cancellationSignal);
   }

   public void beginTransaction(int transactionMode,
       CancellationSignal cancellationSignal) {
      beginTransactionImpl(transactionMode, cancellationSignal);
   }


   /**
    * End a transaction. See beginTransaction for notes about how to use this and when transactions
    * are committed and rolled back.
    *
    * Ends the current transaction and commits or rolls back changes.
    * <p>
    * If this is the outermost transaction (not nested within any other
    * transaction), then the changes are committed if {@link #setTransactionSuccessful}
    * was called on this and all transactions nested within this one.
    * Otherwise, it is rolled back.
    * </p><p>
    * This method must be called exactly once for each call to {@link #beginTransactionNonExclusive}.
    * </p>
    *
    * @throws IllegalStateException if there is no current transaction.
    * @throws SQLiteException if an error occurs.
    * @throws OperationCanceledException if the operation was canceled.
    *
    * @see #beginTransaction
    * @see #setTransactionSuccessful
    */
   public void endTransaction() {
      endTransaction(null);
   }

   /**
    * Gets the database version.
    *
    * @return the database version
    */
   public int getVersion() {
      long version = executeForLongImpl("PRAGMA user_version;", null, null);
      return ((Long) version).intValue();
   }

   /**
    * Sets the database version.
    *
    * @param version the new database version
    */
   public void setVersion(int version) {
      executeForChangedRowCountImpl("PRAGMA user_version = " + version, null, null);
   }

   /**
    * Query the given table, returning a {@link Cursor} over the result set.
    *
    * @param table The table name to compile the query against.
    * @param columns A list of which columns to return. Passing null will
    *            return all columns, which is discouraged to prevent reading
    *            data from storage that isn't going to be used.
    * @param selection A filter declaring which rows to return, formatted as an
    *            SQL WHERE clause (excluding the WHERE itself). Passing null
    *            will return all rows for the given table.
    * @param selectionArgs You may include ?s in selection, which will be
    *         replaced by the values from selectionArgs, in order that they
    *         appear in the selection. The values will be bound as Strings.
    * @param groupBy A filter declaring how to group rows, formatted as an SQL
    *            GROUP BY clause (excluding the GROUP BY itself). Passing null
    *            will cause the rows to not be grouped.
    * @param having A filter declare which row groups to include in the cursor,
    *            if row grouping is being used, formatted as an SQL HAVING
    *            clause (excluding the HAVING itself). Passing null will cause
    *            all row groups to be included, and is required when row
    *            grouping is not being used.
    * @param orderBy How to order the rows, formatted as an SQL ORDER BY clause
    *            (excluding the ORDER BY itself). Passing null will use the
    *            default sort order, which may be unordered.
    * @return A {@link Cursor} object, which is positioned before the first entry. Note that
    * {@link Cursor}s are not synchronized, see the documentation for more details.
    * @see Cursor
    */
   public Cursor query(String table, String[] columns, String selection,
       String[] selectionArgs, String groupBy, String having,
       String orderBy) {

      return query(false, table, columns, selection, selectionArgs, groupBy,
          having, orderBy, null /* limit */);
   }

   /**
    * Query the given table, returning a {@link Cursor} over the result set.
    *
    * @param table The table name to compile the query against.
    * @param columns A list of which columns to return. Passing null will
    *            return all columns, which is discouraged to prevent reading
    *            data from storage that isn't going to be used.
    * @param selection A filter declaring which rows to return, formatted as an
    *            SQL WHERE clause (excluding the WHERE itself). Passing null
    *            will return all rows for the given table.
    * @param selectionArgs You may include ?s in selection, which will be
    *         replaced by the values from selectionArgs, in order that they
    *         appear in the selection. The values will be bound as Strings.
    * @param groupBy A filter declaring how to group rows, formatted as an SQL
    *            GROUP BY clause (excluding the GROUP BY itself). Passing null
    *            will cause the rows to not be grouped.
    * @param having A filter declare which row groups to include in the cursor,
    *            if row grouping is being used, formatted as an SQL HAVING
    *            clause (excluding the HAVING itself). Passing null will cause
    *            all row groups to be included, and is required when row
    *            grouping is not being used.
    * @param orderBy How to order the rows, formatted as an SQL ORDER BY clause
    *            (excluding the ORDER BY itself). Passing null will use the
    *            default sort order, which may be unordered.
    * @param limit Limits the number of rows returned by the query,
    *            formatted as LIMIT clause. Passing null denotes no LIMIT clause.
    * @return A {@link Cursor} object, which is positioned before the first entry. Note that
    * {@link Cursor}s are not synchronized, see the documentation for more details.
    * @see Cursor
    */
   public Cursor query(String table, String[] columns, String selection,
       String[] selectionArgs, String groupBy, String having,
       String orderBy, String limit) {

      return query(false, table, columns, selection, selectionArgs, groupBy,
          having, orderBy, limit);
   }

   /**
    * Query the given URL, returning a {@link Cursor} over the result set.
    *
    * @param distinct true if you want each row to be unique, false otherwise.
    * @param table The table name to compile the query against.
    * @param columns A list of which columns to return. Passing null will
    *            return all columns, which is discouraged to prevent reading
    *            data from storage that isn't going to be used.
    * @param selection A filter declaring which rows to return, formatted as an
    *            SQL WHERE clause (excluding the WHERE itself). Passing null
    *            will return all rows for the given table.
    * @param selectionArgs You may include ?s in selection, which will be
    *         replaced by the values from selectionArgs, in order that they
    *         appear in the selection. The values will be bound as Strings.
    * @param groupBy A filter declaring how to group rows, formatted as an SQL
    *            GROUP BY clause (excluding the GROUP BY itself). Passing null
    *            will cause the rows to not be grouped.
    * @param having A filter declare which row groups to include in the cursor,
    *            if row grouping is being used, formatted as an SQL HAVING
    *            clause (excluding the HAVING itself). Passing null will cause
    *            all row groups to be included, and is required when row
    *            grouping is not being used.
    * @param orderBy How to order the rows, formatted as an SQL ORDER BY clause
    *            (excluding the ORDER BY itself). Passing null will use the
    *            default sort order, which may be unordered.
    * @param limit Limits the number of rows returned by the query,
    *            formatted as LIMIT clause. Passing null denotes no LIMIT clause.
    * @return A {@link Cursor} object, which is positioned before the first entry. Note that
    * {@link Cursor}s are not synchronized, see the documentation for more details.
    * @see Cursor
    */
   public Cursor query(boolean distinct, String table, String[] columns,
       String selection, String[] selectionArgs, String groupBy,
       String having, String orderBy, String limit) {
      return query(distinct, table, columns, selection, selectionArgs, groupBy, having, orderBy,
          limit, null);
   }

   /**
    * Query the given URL, returning a {@link Cursor} over the result set.
    *
    * @param distinct true if you want each row to be unique, false otherwise.
    * @param table The table name to compile the query against.
    * @param columns A list of which columns to return. Passing null will
    *            return all columns, which is discouraged to prevent reading
    *            data from storage that isn't going to be used.
    * @param selection A filter declaring which rows to return, formatted as an
    *            SQL WHERE clause (excluding the WHERE itself). Passing null
    *            will return all rows for the given table.
    * @param selectionArgs You may include ?s in selection, which will be
    *         replaced by the values from selectionArgs, in order that they
    *         appear in the selection. The values will be bound as Strings.
    * @param groupBy A filter declaring how to group rows, formatted as an SQL
    *            GROUP BY clause (excluding the GROUP BY itself). Passing null
    *            will cause the rows to not be grouped.
    * @param having A filter declare which row groups to include in the cursor,
    *            if row grouping is being used, formatted as an SQL HAVING
    *            clause (excluding the HAVING itself). Passing null will cause
    *            all row groups to be included, and is required when row
    *            grouping is not being used.
    * @param orderBy How to order the rows, formatted as an SQL ORDER BY clause
    *            (excluding the ORDER BY itself). Passing null will use the
    *            default sort order, which may be unordered.
    * @param limit Limits the number of rows returned by the query,
    *            formatted as LIMIT clause. Passing null denotes no LIMIT clause.
    * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
    * If the operation is canceled, then {@link OperationCanceledException} will be thrown
    * when the query is executed.
    * @return A {@link Cursor} object, which is positioned before the first entry. Note that
    * {@link Cursor}s are not synchronized, see the documentation for more details.
    * @see Cursor
    */
   public Cursor query(boolean distinct, String table, String[] columns,
       String selection, String[] selectionArgs, String groupBy,
       String having, String orderBy, String limit, CancellationSignal cancellationSignal) {

      String sql = buildQueryString(
          distinct, table, columns, selection, groupBy, having, orderBy, limit);

      return rawQuery(sql, selectionArgs, cancellationSignal);
   }

   /**
    * Runs the provided SQL and returns a {@link Cursor} over the result set.
    *
    * @param sql the SQL query. The SQL string must not be ; terminated
    * @param selectionArgs You may include ?s in where clause in the query,
    *     which will be replaced by the values from selectionArgs. The
    *     values will be bound as Strings.
    * @return A {@link Cursor} object, which is positioned before the first entry. Note that
    * {@link Cursor}s are not synchronized, see the documentation for more details.
    */
   public Cursor rawQuery(String sql, String[] selectionArgs) {
      return rawQuery(sql, selectionArgs, null);
   }

   /**
    * Runs the provided SQL and returns a cursor over the result set.
    *
    * @param sql the SQL query. The SQL string must not be ; terminated
    * @param selectionArgs You may include ?s in where clause in the query,
    *     which will be replaced by the values from selectionArgs. The
    *     values will be bound as Strings.
    * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
    * If the operation is canceled, then {@link OperationCanceledException} will be thrown
    * when the query is executed.
    * @return A {@link Cursor} object, which is positioned before the first entry. Note that
    * {@link Cursor}s are not synchronized, see the documentation for more details.
    */
   public Cursor rawQuery(
       String sql, String[] selectionArgs,
       CancellationSignal cancellationSignal) {
      return rawQueryImpl(sql, selectionArgs, cancellationSignal);
   }

   /**
    * Convenience method for inserting a row into the database.
    *
    * @param table the table to insert the row into
    * @param nullColumnHack optional; may be <code>null</code>.
    *            SQL doesn't allow inserting a completely empty row without
    *            naming at least one column name.  If your provided <code>values</code> is
    *            empty, no column names are known and an empty row can't be inserted.
    *            If not set to null, the <code>nullColumnHack</code> parameter
    *            provides the name of nullable column name to explicitly insert a NULL into
    *            in the case where your <code>values</code> is empty.
    * @param values this map contains the initial column values for the
    *            row. The keys should be the column names and the values the
    *            column values
    * @return the row ID of the newly inserted row, or -1 if an error occurred
    */
   public long insert(String table, String nullColumnHack, ContentValues values) {
      try {
         return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE);
      } catch (SQLException e) {
         getLogger().printStackTrace(e);
         return -1;
      }
   }

   /**
    * Convenience method for inserting a row into the database.
    *
    * @param table the table to insert the row into
    * @param nullColumnHack optional; may be <code>null</code>.
    *            SQL doesn't allow inserting a completely empty row without
    *            naming at least one column name.  If your provided <code>values</code> is
    *            empty, no column names are known and an empty row can't be inserted.
    *            If not set to null, the <code>nullColumnHack</code> parameter
    *            provides the name of nullable column name to explicitly insert a NULL into
    *            in the case where your <code>values</code> is empty.
    * @param values this map contains the initial column values for the
    *            row. The keys should be the column names and the values the
    *            column values
    * @throws SQLException
    * @return the row ID of the newly inserted row, or -1 if an error occurred
    */
   public long insertOrThrow(String table, String nullColumnHack, ContentValues values)
       throws SQLException {
      return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE);
   }

   /**
    * Convenience method for replacing a row in the database.
    *
    * @param table the table in which to replace the row
    * @param nullColumnHack optional; may be <code>null</code>.
    *            SQL doesn't allow inserting a completely empty row without
    *            naming at least one column name.  If your provided <code>initialValues</code> is
    *            empty, no column names are known and an empty row can't be inserted.
    *            If not set to null, the <code>nullColumnHack</code> parameter
    *            provides the name of nullable column name to explicitly insert a NULL into
    *            in the case where your <code>initialValues</code> is empty.
    * @param initialValues this map contains the initial column values for
    *   the row. The key
    * @throws SQLException
    * @return the row ID of the newly inserted row, or -1 if an error occurred
    */
   public long replaceOrThrow(String table, String nullColumnHack,
       ContentValues initialValues) throws SQLException {
      return insertWithOnConflict(table, nullColumnHack, initialValues,
          CONFLICT_REPLACE);
   }

   /**
    * General method for inserting a row into the database.
    *
    * @param table the table to insert the row into
    * @param nullColumnHack optional; may be <code>null</code>.
    *            SQL doesn't allow inserting a completely empty row without
    *            naming at least one column name.  If your provided <code>initialValues</code> is
    *            empty, no column names are known and an empty row can't be inserted.
    *            If not set to null, the <code>nullColumnHack</code> parameter
    *            provides the name of nullable column name to explicitly insert a NULL into
    *            in the case where your <code>initialValues</code> is empty.
    * @param initialValues this map contains the initial column values for the
    *            row. The keys should be the column names and the values the
    *            column values
    * @param conflictAlgorithm for insert conflict resolver
    * @return the row ID of the newly inserted row
    * OR the primary key of the existing row if the input param 'conflictAlgorithm' =
    * {@link #CONFLICT_IGNORE}
    * OR -1 if any error
    */
   public long insertWithOnConflict(String table, String nullColumnHack,
       ContentValues initialValues, int conflictAlgorithm) {
      StringBuilder sql = new StringBuilder();
      sql.append("INSERT");
      sql.append(CONFLICT_VALUES[conflictAlgorithm]);
      sql.append(" INTO ");
      sql.append(table);
      sql.append('(');

      Object[] bindArgs = null;
      int size = (initialValues != null && initialValues.size() > 0)
          ? initialValues.size() : 0;
      if (size > 0) {
         bindArgs = new Object[size];
         int i = 0;
         for (String colName : initialValues.keySet()) {
            sql.append((i > 0) ? "," : "");
            sql.append(colName);
            bindArgs[i++] = initialValues.get(colName);
         }
         sql.append(')');
         sql.append(" VALUES (");
         for (i = 0; i < size; i++) {
            sql.append((i > 0) ? ",?" : "?");
         }
      } else {
         sql.append(nullColumnHack + ") VALUES (NULL");
      }
      sql.append(')');

      return executeForLastInsertedRowIdImpl(sql.toString(), bindArgs, null);
   }

   /**
    * Convenience method for deleting rows in the database.
    *
    * @param table the table to delete from
    * @param whereClause the optional WHERE clause to apply when deleting.
    *            Passing null will delete all rows.
    * @param whereArgs You may include ?s in the where clause, which
    *            will be replaced by the values from whereArgs. The values
    *            will be bound as Strings.
    * @return the number of rows affected if a whereClause is passed in, 0
    *         otherwise. To remove all rows and get a count pass "1" as the
    *         whereClause.
    */
   public int delete(String table, String whereClause, String[] whereArgs) {
      return executeForChangedRowCountImpl("DELETE FROM " + table +
          (!TextUtils.isEmpty(whereClause) ? " WHERE " + whereClause : ""), whereArgs, null);
   }

   /**
    * Convenience method for updating rows in the database.
    *
    * @param table the table to update in
    * @param values a map from column names to new column values. null is a
    *            valid value that will be translated to NULL.
    * @param whereClause the optional WHERE clause to apply when updating.
    *            Passing null will update all rows.
    * @param whereArgs You may include ?s in the where clause, which
    *            will be replaced by the values from whereArgs. The values
    *            will be bound as Strings.
    * @return the number of rows affected
    */
   public int update(String table, ContentValues values, String whereClause, String[] whereArgs) {
      return updateWithOnConflict(table, values, whereClause, whereArgs, CONFLICT_NONE);
   }

   /**
    * Convenience method for updating rows in the database.
    *
    * @param table the table to update in
    * @param values a map from column names to new column values. null is a
    *            valid value that will be translated to NULL.
    * @param whereClause the optional WHERE clause to apply when updating.
    *            Passing null will update all rows.
    * @param whereArgs You may include ?s in the where clause, which
    *            will be replaced by the values from whereArgs. The values
    *            will be bound as Strings.
    * @param conflictAlgorithm for update conflict resolver
    * @return the number of rows affected
    */
   public int updateWithOnConflict(String table, ContentValues values,
       String whereClause, String[] whereArgs, int conflictAlgorithm) {
      if (values == null || values.size() == 0) {
         throw new IllegalArgumentException("Empty values");
      }

      StringBuilder sql = new StringBuilder(120);
      sql.append("UPDATE ");
      sql.append(CONFLICT_VALUES[conflictAlgorithm]);
      sql.append(table);
      sql.append(" SET ");

      // move all bind args to one array
      int setValuesSize = values.size();
      int bindArgsSize = (whereArgs == null) ? setValuesSize : (setValuesSize + whereArgs.length);
      Object[] bindArgs = new Object[bindArgsSize];
      int i = 0;
      for (String colName : values.keySet()) {
         sql.append((i > 0) ? "," : "");
         sql.append(colName);
         bindArgs[i++] = values.get(colName);
         sql.append("=?");
      }
      if (whereArgs != null) {
         for (i = setValuesSize; i < bindArgsSize; i++) {
            bindArgs[i] = whereArgs[i - setValuesSize];
         }
      }
      if (!TextUtils.isEmpty(whereClause)) {
         sql.append(" WHERE ");
         sql.append(whereClause);
      }

      return executeForChangedRowCountImpl(sql.toString(), bindArgs, null);
   }

   /**
    * Execute a single SQL statement that is NOT a SELECT/INSERT/UPDATE/DELETE.
    * <p>
    * For INSERT statements, use any of the following instead.
    * <ul>
    *   <li>{@link #insert(String, String, ContentValues)}</li>
    *   <li>{@link #insertOrThrow(String, String, ContentValues)}</li>
    *   <li>{@link #insertWithOnConflict(String, String, ContentValues, int)}</li>
    * </ul>
    * <p>
    * For UPDATE statements, use any of the following instead.
    * <ul>
    *   <li>{@link #update(String, ContentValues, String, String[])}</li>
    *   <li>{@link #updateWithOnConflict(String, ContentValues, String, String[], int)}</li>
    * </ul>
    * <p>
    * For DELETE statements, use any of the following instead.
    * <ul>
    *   <li>{@link #delete(String, String, String[])}</li>
    * </ul>
    * <p>
    * For example, the following are good candidates for using this method:
    * <ul>
    *   <li>ALTER TABLE</li>
    *   <li>CREATE or DROP table / trigger / view / index / virtual table</li>
    *   <li>REINDEX</li>
    *   <li>RELEASE</li>
    *   <li>SAVEPOINT</li>
    *   <li>PRAGMA that returns no data</li>
    * </ul>
    * </p>
    * <p>
    * When using {@link #ENABLE_WRITE_AHEAD_LOGGING}, journal_mode is
    * automatically managed by this class. So, do not set journal_mode
    * using "PRAGMA journal_mode'<value>" statement if your app is using
    * {@link #ENABLE_WRITE_AHEAD_LOGGING}
    * </p>
    *
    * @param sql the SQL statement to be executed. Multiple statements separated by semicolons are
    * not supported.
    * @param bindArgs only byte[], String, Long and Double are supported in bindArgs.
    * @throws SQLException if the SQL string is invalid
    */
   public void execSQL(String sql, Object[] bindArgs) throws SQLException {
      execute(sql, bindArgs, null);
   }

   /**********************************************************************************************
    * PROTECTED ACCESS
    *
    * Methods below this line use the mConnectionPtrMutex to guard
    * their data structures and internals.
    * *******************************************************************************************/

   private void closeImpl(boolean finalized) {

      // during AndroidUnitTest testing, the directory might be
      // torn down before the finalize has completed.
      File f = new File(ODKFileUtils.getLoggingFolder(getAppName()));
      boolean hasLoggingDirectory = ( f.exists() && f.isDirectory() );

      synchronized (mConnectionPtrMutex) {
         if (finalized && (mAllocationReference != null)) {
            String message = mAllocationReference + " was acquired but never released.";
            try {
               if ( hasLoggingDirectory ) {
                  getLogger().e(TAG, message);
               } else {
                  Log.e(TAG, message);
               }
            } catch ( Throwable t) {
               // ignore...
            }
         }
         mAllocationReference = null;

         if (mConnectionPtr != 0L) {
            final int cookie = mRecentOperations.beginOperation(mSessionQualifier, "close", null,
                null);
            try {
               if (!mActiveCursors.isEmpty()) {
                  if (!finalized) {
                     /**
                      * If there are active cursors, then if we are not finalizing, we are in
                      * a weird state, since the cursors should be holding references to the
                      * connection and not allowing it to die.
                      */
                     if ( hasLoggingDirectory ) {
                        getLogger().e(TAG, "connection:" + mSessionQualifier
                            + " Logic error! There are open cursors when calling close()");
                     } else {
                        Log.e(TAG, "connection:" + mSessionQualifier
                            + " Logic error! There are open cursors when calling close()");
                     }
                  } else {
                     /**
                      * Only if we are finalized is it safe to do what follows....
                      *
                      * If we were to access cursor outside of the finalizer, we
                      * might get into deadlock with another thread holding the
                      * SQLiteCursor's impl mutex and attempting to gain our
                      * mConnectionPtrMutex mutex while we do the reverse when
                      * we call cursor.close().
                      *
                      * That is not possible when finalized, so we can safely call
                      * cursor.close() with our inverted mutex order.
                      */
                     ArrayList<SQLiteCursor> cursors =
                         new ArrayList<SQLiteCursor>(mActiveCursors.keySet());

                     // close cursors -- this releases their prepared statements
                     for (SQLiteCursor cursor : cursors) {
                        if ( cursor != null ) {
                           if ( hasLoggingDirectory ) {
                              getLogger().e(TAG, "connection:" + getAppName() + " "
                                  + mSessionQualifier
                                  + " Program error! A cursor:" + cursor.getSql()
                                  + " is open when finalized");
                           } else {
                              Log.e(TAG, "connection:" + getAppName() + " "
                                  + mSessionQualifier
                                  + " Program error! A cursor:" + cursor.getSql()
                                  + " is open when finalized");
                           }
                           cursor.close();
                        }
                     }
                     mActiveCursors.clear();
                  }
               }
               // and now evict the now-released prepared statements
               mPreparedStatementCache.evictAll();

               nativeClose(mConnectionPtr);
            } catch ( Throwable t) {
               mRecentOperations.failOperation(cookie, t);
               throw t;
            } finally {
               mConnectionPtr = 0L;
               mRecentOperations.endOperation(cookie);
            }
         }
      }
   }

   /**
    * Returns true if the database is currently open.
    *
    * @return True if the database is currently open (has not been closed).
    */
   public boolean isOpen() {
      synchronized (mConnectionPtrMutex) {
         return (mConnectionPtr != 0L);
      }
   }

   private void openImpl() {
      if ((mConfiguration.openFlags & ENABLE_WRITE_AHEAD_LOGGING) == 0) {
         throw new IllegalStateException("Only WAL mode is allowed");
      }

      synchronized (mConnectionPtrMutex) {
         mConnectionPtr = nativeOpen(mConfiguration.path, mConfiguration.openFlags, mConfiguration.label,
             SQLiteDebug.DEBUG_SQL_STATEMENTS, SQLiteDebug.DEBUG_SQL_TIME);

         // Register custom functions.
         final int functionCount = mConfiguration.customFunctions.size();
         for (int i = 0; i < functionCount; i++) {
            SQLiteCustomFunction function = mConfiguration.customFunctions.get(i);
            nativeRegisterCustomFunction(mConnectionPtr, function);
         }

         {
            final long newValue = SQLiteGlobal.getDefaultPageSize();
            long value = executeForLongImpl("PRAGMA page_size", null, null);
            if (value != newValue) {
               executeImpl("PRAGMA page_size=" + newValue, null, null);
            }
         }

         {
            final long newValue = mConfiguration.foreignKeyConstraintsEnabled ? 1 : 0;
            long value = executeForLongImpl("PRAGMA foreign_keys", null, null);
            if (value != newValue) {
               executeImpl("PRAGMA foreign_keys=" + newValue, null, null);
            }
         }

         {
            final long newValue = SQLiteGlobal.getJournalSizeLimit();
            long value = executeForLongImpl("PRAGMA journal_size_limit", null, null);
            if (value != newValue) {
               executeForLongImpl("PRAGMA journal_size_limit=" + newValue, null, null);
            }
         }

         {
            final long newValue = SQLiteGlobal.getWALAutoCheckpoint();
            long value = executeForLongImpl("PRAGMA wal_autocheckpoint", null, null);
            if (value != newValue) {
               executeForLongImpl("PRAGMA wal_autocheckpoint=" + newValue, null, null);
            }
         }

         setLockingMode("NORMAL");
         setJournalMode("WAL");
         setSyncMode(SQLiteGlobal.getWALSyncMode());
         setBusyTimeout();
      }
   }

   private void setBusyTimeout() {
      final long newValue = 5000L;
      long value = executeForLongImpl("PRAGMA busy_timeout", null, null);
      if (value != newValue) {
         getLogger().w(TAG,"busy_timeout is not " + newValue + " but " + value);
         // TODO: fix this when C++ code is updated.
         // sqlite code does a strcmp that only recognizes 'busy_timeout', making it
         // impossible to update this value.
         // executeImpl("PRAGMA busy_timeout=" + newValue, null, null);
      }
   }

   private void setLockingMode(String newValue) {
      String value = executeForStringImpl("PRAGMA locking_mode", null, null);
      if (!value.equalsIgnoreCase(newValue)) {
         String result = executeForStringImpl("PRAGMA locking_mode=" + newValue, null, null);
         if (result.equalsIgnoreCase(newValue)) {
            return;
         }
         // PRAGMA locking_mode silently fails and returns the original journal
         // mode in some cases if the locking mode could not be changed.
         getLogger().e(TAG, "Could not change the database locking mode of '"
             + mConfiguration.label + "' from '" + value + "' to '" + newValue);
         throw new IllegalStateException("Unable to change the locking mode");
      }
   }

   private void setJournalMode(String newValue) {
      String value = executeForStringImpl("PRAGMA journal_mode", null, null);
      if (!value.equalsIgnoreCase(newValue)) {
         String result = executeForStringImpl("PRAGMA journal_mode=" + newValue, null, null);
         if (result.equalsIgnoreCase(newValue)) {
            return;
         }
         // PRAGMA journal_mode silently fails and returns the original journal
         // mode in some cases if the journal mode could not be changed.
         getLogger().e(TAG, "Could not change the database journal mode of '"
             + mConfiguration.label + "' from '" + value + "' to '" + newValue
             + "' because the database is locked.  This usually means that "
             + "there are other open connections to the database which prevents "
             + "the database from enabling or disabling write-ahead logging mode.  "
             + "Proceeding without changing the journal mode.");
         throw new IllegalStateException("Unable to change the journal mode");
      }
   }

   private void setSyncMode(String newValue) {
      String value = executeForStringImpl("PRAGMA synchronous", null, null);
      if (!canonicalizeSyncMode(value).equalsIgnoreCase(
          canonicalizeSyncMode(newValue))) {
         executeImpl("PRAGMA synchronous=" + newValue, null, null);
      }
   }

   // CancellationSignal.OnCancelListener callback.
   // This method may be called on a different thread than the executing statement.
   // However, it will only be called between calls to attachCancellationSignal and
   // detachCancellationSignal, while a statement is executing.  We can safely assume
   // that the SQLite connection is still alive.
   @Override
   public void onCancel() {
      synchronized (mConnectionPtrMutex) {
         if (mConnectionPtr == 0L) {
            throw new SQLiteException("connection closed");
         }
         nativeCancel(mConnectionPtr);
      }
   }

   private void beginTransactionImpl(int transactionMode,
       CancellationSignal cancellationSignal) {
      if (cancellationSignal != null) {
         cancellationSignal.throwIfCanceled();
      }

      synchronized (mConnectionPtrMutex) {
         if (mConnectionPtr == 0L) {
            throw new SQLiteException("connection closed");
         }
         if (mTransactionManager.beginTransaction(transactionMode)) {
            boolean success = false;
            try {
               // Execute SQL might throw a runtime exception.
               switch (transactionMode) {
               case TRANSACTION_MODE_IMMEDIATE:
                  executeImpl("BEGIN IMMEDIATE;", null, cancellationSignal); // might throw
                  break;
               case TRANSACTION_MODE_EXCLUSIVE:
                  executeImpl("BEGIN EXCLUSIVE;", null, cancellationSignal); // might throw
                  break;
               default:
                  executeImpl("BEGIN;", null, cancellationSignal); // might throw
                  break;
               }
               success = true;
            } finally {
               if (!success) {
                  mTransactionManager.cancelTransaction();
               }
            }
         }
      }
   }
   /**
    * Returns true if the current thread has a transaction pending.
    *
    * @return True if the current thread is in a transaction.
    */
   public boolean inTransaction() {
      synchronized (mConnectionPtrMutex) {
         if (mConnectionPtr == 0L) {
            throw new SQLiteException("connection closed");
         }
         return mTransactionManager.hasTransaction();
      }
   }


   private void commitTransactionImpl(CancellationSignal cancellationSignal) {
      synchronized (mConnectionPtrMutex) {
         if (mConnectionPtr == 0L) {
            throw new SQLiteException("connection closed");
         }
         setTransactionSuccessful();
         endTransaction(cancellationSignal);
      }
   }

   /**
    * Marks the current transaction as successful. Do not do any more database work between
    * calling this and calling endTransaction. Do as little non-database work as possible in that
    * situation too. If any errors are encountered between this and endTransaction the transaction
    * will still be committed.
    *
    * @throws IllegalStateException if the current thread is not in a transaction or the
    * transaction is already marked as successful.
    *
    * Marks the current transaction as having completed successfully.
    * <p>
    * This method can be called at most once between {@link #beginTransactionNonExclusive} and
    * {@link #endTransaction} to indicate that the changes made by the transaction should be
    * committed.  If this method is not called, the changes will be rolled back
    * when the transaction is ended.
    * </p>
    *
    * @throws IllegalStateException if there is no current transaction, or if
    * {@link #setTransactionSuccessful} has already been called for the current transaction.
    *
    * @see #beginTransactionNonExclusive
    * @see #endTransaction
    */
   public void setTransactionSuccessful() {
      synchronized (mConnectionPtrMutex) {
         if (mConnectionPtr == 0L) {
            throw new SQLiteException("connection closed");
         }
         mTransactionManager.setTransactionSuccessful();
      }
   }

   /**
    *
    * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
    *
    * @throws IllegalStateException if there is no current transaction.
    * @throws SQLiteException if an error occurs.
    * @throws OperationCanceledException if the operation was canceled.
    *
    * @see #beginTransaction
    * @see #setTransactionSuccessful
    */
   public void endTransaction(CancellationSignal cancellationSignal) {
      if (cancellationSignal != null) {
         cancellationSignal.throwIfCanceled();
      }
      synchronized (mConnectionPtrMutex) {
         if (mConnectionPtr == 0L) {
            throw new SQLiteException("connection closed");
         }
         SQLiteTransactionManager.TransactionOutcome outcome = mTransactionManager.endTransaction();
         // do nothing on the no-action outcome
         if (outcome == SQLiteTransactionManager.TransactionOutcome.COMMIT_ACTION) {
            executeImpl("COMMIT;", null, cancellationSignal); // might throw
         } else if (outcome == SQLiteTransactionManager.TransactionOutcome.ROLLBACK_ACTION) {
            executeImpl("ROLLBACK;", null, cancellationSignal); // might throw
         }
      }
   }


    /**
     * Executes a statement that does not return a result.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    private void executeImpl(String sql, Object[] bindArgs, CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

       synchronized (mConnectionPtrMutex) {
          if (mConnectionPtr == 0L) {
             throw new SQLiteException("connection closed");
          }
          final int cookie = mRecentOperations
              .beginOperation(mSessionQualifier, "executeImpl", sql, bindArgs);
          try {
             final PreparedStatement statement = mPreparedStatementCache.acquirePreparedStatement(sql);
             try {
                bindArguments(statement, bindArgs);
                attachCancellationSignal(cancellationSignal);
                try {
                   nativeExecute(mConnectionPtr, statement.mStatementPtr);
                } finally {
                   detachCancellationSignal(cancellationSignal);
                }
             } finally {
                mPreparedStatementCache.releasePreparedStatement(statement);
             }
          } catch (Throwable t) {
             mRecentOperations.failOperation(cookie, t);
             throw t;
          } finally {
             mRecentOperations.endOperation(cookie);
          }
       }
    }

    /**
     * Executes a statement that returns a single <code>long</code> result.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The value of the first column in the first row of the result set
     * as a <code>long</code>, or zero if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    private long executeForLongImpl(String sql, Object[] bindArgs,
        CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

       synchronized (mConnectionPtrMutex) {
          if (mConnectionPtr == 0L) {
             throw new SQLiteException("connection closed");
          }
          final int cookie = mRecentOperations
              .beginOperation(mSessionQualifier, "executeForLongImpl", sql, bindArgs);
          try {
             final PreparedStatement statement = mPreparedStatementCache.acquirePreparedStatement(sql);
             try {
                bindArguments(statement, bindArgs);
                attachCancellationSignal(cancellationSignal);
                try {
                   return nativeExecuteForLong(mConnectionPtr, statement.mStatementPtr);
                } finally {
                   detachCancellationSignal(cancellationSignal);
                }
             } finally {
                mPreparedStatementCache.releasePreparedStatement(statement);
             }
          } catch (Throwable t) {
             mRecentOperations.failOperation(cookie, t);
             throw t;
          } finally {
             mRecentOperations.endOperation(cookie);
          }
       }
    }

    /**
     * Executes a statement that returns a single {@link String} result.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The value of the first column in the first row of the result set
     * as a <code>String</code>, or null if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    private String executeForStringImpl(String sql, Object[] bindArgs,
        CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

       synchronized (mConnectionPtrMutex) {
          if (mConnectionPtr == 0L) {
             throw new SQLiteException("connection closed");
          }
          final int cookie = mRecentOperations
              .beginOperation(mSessionQualifier, "executeForStringImpl", sql, bindArgs);
          try {
             final PreparedStatement statement = mPreparedStatementCache.acquirePreparedStatement(sql);
             try {
                bindArguments(statement, bindArgs);
                attachCancellationSignal(cancellationSignal);
                try {
                   return nativeExecuteForString(mConnectionPtr, statement.mStatementPtr);
                } finally {
                   detachCancellationSignal(cancellationSignal);
                }
             } finally {
                mPreparedStatementCache.releasePreparedStatement(statement);
             }
          } catch (Throwable t) {
             mRecentOperations.failOperation(cookie, t);
             throw t;
          } finally {
             mRecentOperations.endOperation(cookie);
          }
       }
    }

    /**
     * Executes a statement that returns a single BLOB result as a
     * file descriptor to a shared memory region.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The file descriptor for a shared memory region that contains
     * the value of the first column in the first row of the result set as a BLOB,
     * or null if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    private ParcelFileDescriptor executeForBlobFileDescriptorImpl(String sql, Object[] bindArgs,
        CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

       synchronized (mConnectionPtrMutex) {
          if (mConnectionPtr == 0L) {
             throw new SQLiteException("connection closed");
          }
          final int cookie = mRecentOperations
              .beginOperation(mSessionQualifier, "executeForBlobFileDescriptorImpl", sql, bindArgs);
          try {
             final PreparedStatement statement = mPreparedStatementCache.acquirePreparedStatement(sql);
             try {
                bindArguments(statement, bindArgs);
                attachCancellationSignal(cancellationSignal);
                try {
                   int fd = nativeExecuteForBlobFileDescriptor(mConnectionPtr, statement.mStatementPtr);
                   return fd >= 0 ? ParcelFileDescriptor.adoptFd(fd) : null;
                } finally {
                   detachCancellationSignal(cancellationSignal);
                }
             } finally {
                mPreparedStatementCache.releasePreparedStatement(statement);
             }
          } catch (Throwable t) {
             mRecentOperations.failOperation(cookie, t);
             throw t;
          } finally {
             mRecentOperations.endOperation(cookie);
          }
       }
    }

    /**
     * Executes a statement that returns a count of the number of rows
     * that were changed.  Use for UPDATE or DELETE SQL statements.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The number of rows that were changed.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    private int executeForChangedRowCountImpl(String sql, Object[] bindArgs,
        CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }
       synchronized (mConnectionPtrMutex) {
          if (mConnectionPtr == 0L) {
             throw new SQLiteException("connection closed");
          }

          int changedRows = 0;
          final int cookie = mRecentOperations
              .beginOperation(mSessionQualifier, "executeForChangedRowCountImpl", sql, bindArgs);
          try {
             final PreparedStatement statement = mPreparedStatementCache.acquirePreparedStatement(sql);
             try {
                bindArguments(statement, bindArgs);
                attachCancellationSignal(cancellationSignal);
                try {
                   changedRows = nativeExecuteForChangedRowCount(mConnectionPtr, statement.mStatementPtr);
                   return changedRows;
                } finally {
                   detachCancellationSignal(cancellationSignal);
                }
             } finally {
                mPreparedStatementCache.releasePreparedStatement(statement);
             }
          } catch (Throwable t) {
             mRecentOperations.failOperation(cookie, t);
             throw t;
          } finally {
             mRecentOperations.endOperationDeferLogAdditional(cookie, "changedRows=" + changedRows);
          }
       }
    }

   /**
    * Runs the provided SQL and returns a cursor over the result set.
    *
    * @param sql the SQL query. The SQL string must not be ; terminated
    * @param selectionArgs You may include ?s in where clause in the query,
    *     which will be replaced by the values from selectionArgs. The
    *     values will be bound as Strings.
    * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
    * If the operation is canceled, then {@link OperationCanceledException} will be thrown
    * when the query is executed.
    * @return A {@link Cursor} object, which is positioned before the first entry. Note that
    * {@link Cursor}s are not synchronized, see the documentation for more details.
    */
   private Cursor rawQueryImpl(
       String sql, String[] selectionArgs,
       CancellationSignal cancellationSignal) {

      if (sql == null) {
         throw new IllegalArgumentException("sql must not be null.");
      }

      if (cancellationSignal != null) {
         cancellationSignal.throwIfCanceled();
      }

      synchronized (mConnectionPtrMutex) {

         SQLiteStatementInfo info = new SQLiteStatementInfo();

         if (mConnectionPtr == 0L) {
            throw new SQLiteException("connection closed");
         }

         final int cookie = mRecentOperations.beginOperation(mSessionQualifier, "prepare", sql,
             null);
         try {
            final PreparedStatement statement = mPreparedStatementCache.acquirePreparedStatement(
                sql);
            try {
               if (info != null) {
                  info.numParameters = statement.mNumParameters;
                  info.readOnly = statement.mReadOnly;

                  final int columnCount = nativeGetColumnCount(mConnectionPtr, statement.mStatementPtr);
                  if (columnCount == 0) {
                     info.columnNames = EMPTY_STRING_ARRAY;
                  } else {
                     info.columnNames = new String[columnCount];
                     for (int i = 0; i < columnCount; i++) {
                        info.columnNames[i] = nativeGetColumnName(mConnectionPtr,
                            statement.mStatementPtr, i);
                     }
                  }
               }
            } finally {
               mPreparedStatementCache.releasePreparedStatement(statement);
            }
         } catch (Throwable t) {
            mRecentOperations.failOperation(cookie, t);
            throw t;
         } finally {
            mRecentOperations.endOperation(cookie);
         }

         int selectionArgLength = (selectionArgs == null) ? 0 : selectionArgs.length;
         if (selectionArgLength != info.numParameters) {
            throw new IllegalArgumentException(
                "Incorrect number of bind arguments supplied.  " + selectionArgLength + " arguments "
                    + "were provided but the statement needs " + info.numParameters + " arguments.");
         }
         try {
            SQLiteCursor cursor = new SQLiteCursor(this, info.columnNames, sql, selectionArgs,
                cancellationSignal);
            mActiveCursors.put(cursor,this);
            return cursor;
         } catch (RuntimeException ex) {
            throw ex;
         }
      }
   }

   void releaseCursor(SQLiteCursor cursor) {
      synchronized (mConnectionPtrMutex) {
         mActiveCursors.remove(cursor);
      }
   }

    /**
     * Executes a statement that returns the row id of the last row inserted
     * by the statement.  Use for INSERT SQL statements.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The row id of the last row that was inserted, or 0 if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    private long executeForLastInsertedRowIdImpl(String sql, Object[] bindArgs,
        CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

       synchronized (mConnectionPtrMutex) {
          if (mConnectionPtr == 0L) {
             throw new SQLiteException("connection closed");
          }

          final int cookie = mRecentOperations
              .beginOperation(mSessionQualifier, "executeForLastInsertedRowIdImpl", sql, bindArgs);
          try {
             final PreparedStatement statement = mPreparedStatementCache.acquirePreparedStatement(sql);
             try {
                bindArguments(statement, bindArgs);
                attachCancellationSignal(cancellationSignal);
                try {
                   return nativeExecuteForLastInsertedRowId(mConnectionPtr, statement.mStatementPtr);
                } finally {
                   detachCancellationSignal(cancellationSignal);
                }
             } finally {
                mPreparedStatementCache.releasePreparedStatement(statement);
             }
          } catch (Throwable t) {
             mRecentOperations.failOperation(cookie, t);
             throw t;
          } finally {
             mRecentOperations.endOperation(cookie);
          }
       }
    }

    /**
     * Executes a statement and populates the specified {@link CursorWindow}
     * with a range of results.  Returns the number of rows that were counted
     * during query execution.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param window The cursor window to clear and fill.
     * @param startPos The start position for filling the window.
     * @param requiredPos The position of a row that MUST be in the window.
     * If it won't fit, then the query should discard part of what it filled
     * so that it does.  Must be greater than or equal to <code>startPos</code>.
     * @param countAllRows True to count all rows that the query would return
     * regagless of whether they fit in the window.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The number of rows that were counted during query execution.  Might
     * not be all rows in the result set unless <code>countAllRows</code> is true.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    private int executeForCursorWindowImpl(String sql, Object[] bindArgs, CursorWindow window,
        int startPos, int requiredPos, boolean countAllRows, CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }
        if (window == null) {
            throw new IllegalArgumentException("window must not be null.");
        }

        window.acquireReference();
        try {
            int actualPos = -1;
            int countedRows = -1;
            int filledRows = -1;
           synchronized (mConnectionPtrMutex) {
              if (mConnectionPtr == 0L) {
                 throw new SQLiteException("connection closed");
              }
              final int cookie = mRecentOperations
                  .beginOperation(mSessionQualifier, "executeForCursorWindowImpl", sql, bindArgs);
              try {
                 final PreparedStatement statement = mPreparedStatementCache.acquirePreparedStatement(sql);
                 try {
                    bindArguments(statement, bindArgs);
                    attachCancellationSignal(cancellationSignal);
                    try {
                       final long result = nativeExecuteForCursorWindow(mConnectionPtr, statement.mStatementPtr, window,
                           startPos, requiredPos, countAllRows);
                       actualPos = (int) (result >> 32);
                       countedRows = (int) result;
                       filledRows = window.getNumRows();
                       window.setStartPosition(actualPos);
                       return countedRows;
                    } finally {
                       detachCancellationSignal(cancellationSignal);
                    }
                 } finally {
                    mPreparedStatementCache.releasePreparedStatement(statement);
                 }
              } catch (Throwable t) {
                 mRecentOperations.failOperation(cookie, t);
                 throw t;
              } finally {
                 mRecentOperations.endOperationDeferLogAdditional(cookie,
                     "window='" + window + "', startPos=" + startPos + ", actualPos=" + actualPos + ", filledRows=" + filledRows
                         + ", countedRows=" + countedRows);
              }
           }
        } finally {
            window.releaseReference();
        }
    }

    private void attachCancellationSignal(CancellationSignal cancellationSignal) {
        if (cancellationSignal != null) {
            cancellationSignal.throwIfCanceled();

           synchronized (mConnectionPtrMutex) {
              if (mConnectionPtr == 0L) {
                 throw new SQLiteException("connection closed");
              }

              mCancellationSignalAttachCount += 1;
              if (mCancellationSignalAttachCount == 1) {
                 // Reset cancellation flag before executing the statement.
                 nativeResetCancel(mConnectionPtr, true /*cancelable*/);

                 // After this point, onCancel() may be called concurrently.
                 cancellationSignal.setOnCancelListener(this);
              }
           }
        }
    }

    private void detachCancellationSignal(CancellationSignal cancellationSignal) {
        if (cancellationSignal != null) {
           synchronized (mConnectionPtrMutex) {
              if (mConnectionPtr == 0L) {
                 throw new SQLiteException("connection closed");
              }

              assert mCancellationSignalAttachCount > 0;

              mCancellationSignalAttachCount -= 1;
              if (mCancellationSignalAttachCount == 0) {
                 // After this point, onCancel() cannot be called concurrently.
                 cancellationSignal.setOnCancelListener(null);

                 // Reset cancellation flag after executing the statement.
                 nativeResetCancel(mConnectionPtr, false /*cancelable*/);
              }
           }
        }
    }

    private void bindArguments(PreparedStatement statement, Object[] bindArgs) {
        final int count = bindArgs != null ? bindArgs.length : 0;
        if (count != statement.mNumParameters) {
            throw new SQLiteBindOrColumnIndexOutOfRangeException(
                    "Expected " + statement.mNumParameters + " bind arguments but "
                    + count + " were provided.");
        }
        if (count == 0) {
            return;
        }

        final long statementPtr = statement.mStatementPtr;
        for (int i = 0; i < count; i++) {
            final Object arg = bindArgs[i];
            switch (getTypeOfObject(arg)) {
                case Cursor.FIELD_TYPE_NULL:
                    nativeBindNull(mConnectionPtr, statementPtr, i + 1);
                    break;
                case Cursor.FIELD_TYPE_INTEGER:
                    nativeBindLong(mConnectionPtr, statementPtr, i + 1,
                            ((Number)arg).longValue());
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    nativeBindDouble(mConnectionPtr, statementPtr, i + 1,
                            ((Number)arg).doubleValue());
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                    nativeBindBlob(mConnectionPtr, statementPtr, i + 1, (byte[])arg);
                    break;
                case Cursor.FIELD_TYPE_STRING:
                default:
                    if (arg instanceof Boolean) {
                        // Provide compatibility with legacy applications which may pass
                        // Boolean values in bind args.
                        nativeBindLong(mConnectionPtr, statementPtr, i + 1,
                                ((Boolean)arg).booleanValue() ? 1 : 0);
                    } else {
                        nativeBindString(mConnectionPtr, statementPtr, i + 1, arg.toString());
                    }
                    break;
            }
        }
    }

    /**
     * Dumps debugging information about this connection, in the case where the
     * caller might not actually own the connection.
     *
     * This function is written so that it may be called by a thread that does not
     * own the connection.  We need to be very careful because the connection state is
     * not synchronized.
     *
     * At worst, the method may return stale or slightly wrong data, however
     * it should not crash.  This is ok as it is only used for diagnostic purposes.
     *
     * @param b The StringBuilder to receive the dump, not null.
     * @param verbose True to dump more verbose information.
     */
    public void dump(StringBuilder b, boolean verbose) {
        b.append("SessionQualifier: ").append(mSessionQualifier).append("\n");
        if (verbose) {
           synchronized (mConnectionPtrMutex) {
              b.append("connectionPtr: 0x").append(Long.toHexString(mConnectionPtr)).append("\n");
           }
           b.append("lookaside: ").append(getLookaside()).append("\n");
           b.append("preparedStatementCache hitCount: ")
               .append(getPreparedStatementCacheHitCount()).append(" missCount: ")
               .append(getPreparedStatementCacheMissCount()).append(" size: ")
               .append(getPreparedStatementCacheSize()).append("\n");
           mPreparedStatementCache.dump(b);
        }
    }

   public int getLookaside() {
      synchronized (mConnectionPtrMutex) {
         if ( mConnectionPtr != 0L ) {
            return nativeGetDbLookaside(mConnectionPtr);
         } else {
            return 0;
         }
      }
   }

   public int getPreparedStatementCacheHitCount() {
      return mPreparedStatementCache.hitCount();
   }

   public int getPreparedStatementCacheMissCount() {
      return mPreparedStatementCache.missCount();
   }

   public int getPreparedStatementCacheSize() {
      return mPreparedStatementCache.size();
   }

    @Override
    public String toString() {
        return "SQLiteConnection: " + mConfiguration.path + " (" + mSessionQualifier + ")";
    }

   private final PreparedStatementCache mPreparedStatementCache;


   private final class PreparedStatementCache {

       private final class PreparedStatementCacheImpl
            extends LruCache<String, PreparedStatement> {
          public PreparedStatementCacheImpl(int size) {
             super(size);
          }

          @Override protected void entryRemoved(boolean evicted, String key, PreparedStatement oldValue, PreparedStatement newValue) {
             if (!oldValue.mInUse) {
                releasePreparedStatement(oldValue);
             }
          }

          public void dump(StringBuilder b) {
             b.append("  Prepared statement cache:\n");
             Map<String, PreparedStatement> cache = snapshot();
             if (!cache.isEmpty()) {
                int i = 0;
                for (Map.Entry<String, PreparedStatement> entry : cache.entrySet()) {
                   PreparedStatement statement = entry.getValue();
                   String sql = entry.getKey();
                   b.append("    " + i
                       + ": statementPtr=0x" + Long.toHexString(statement.mStatementPtr)
                       + ", numParameters=" + statement.mNumParameters
                       + ", type=" + statement.mType + ", readOnly="
                       + statement.mReadOnly + ", sql=\""
                       + AppNameSharedStateContainer.trimSqlForDisplay(sql) + "\"\n");
                   i += 1;
                }
             } else {
                b.append("    <none>\n");
             }
          }
       }

       private final PreparedStatementCacheImpl impl;

       private PreparedStatement mPreparedStatementPool;

      PreparedStatementCache(int size) {
         impl = new PreparedStatementCacheImpl(size);
      }

      public void dump(StringBuilder b) {
         synchronized (impl) {
            impl.dump(b);
         }
      }

      /**
       * Caller must hold mConnectionPtrMutex before
       * calling this method.
       */
      void evictAll() {
         synchronized (impl) {
            // may trigger releasePreparedStatement()
            impl.evictAll();
         }
      }

      int hitCount() {
         synchronized (impl) {
            return impl.hitCount();
         }
      }

      int missCount() {
         synchronized (impl) {
            return impl.missCount();
         }
      }

      int size() {
         synchronized (impl) {
            return impl.size();
         }
      }

      /**
       * Caller must hold mConnectionPtrMutex before calling this method.
       *
       * @param sql
       * @return
       */
      PreparedStatement acquirePreparedStatement(String sql) {
         synchronized (impl) {
            // see if we have a not-in-use one already in the cache...
            PreparedStatement existing = impl.get(sql);

            if (existing != null && !existing.mInUse) {
               // we found one - mark it as in-use and return it
               existing.mInUse = true;
               return existing;
            }

            PreparedStatement statement = null;
            // Either there is an in-use statement in the cache or the
            // statement is not yet in the cache. Create a new statement.
            final long statementPtr = nativePrepareStatement(mConnectionPtr, sql);
            try {
               final int numParameters = nativeGetParameterCount(mConnectionPtr, statementPtr);
               final int type = DatabaseUtils.getSqlStatementType(sql);
               final boolean readOnly = nativeIsReadOnly(mConnectionPtr, statementPtr);
               // and build up the statement.
               statement = obtainPreparedStatement(sql, statementPtr, numParameters, type, readOnly);

               if ((existing == null) && isCacheable(type)) {
                  // put may trigger releasePreparedStatement()
                  impl.put(sql, statement);
               }
            } catch (RuntimeException ex) {
               // Finalize the statement if an exception occurred and we did not add
               // it to the cache.  If it is already in the cache, then leave it there.
               PreparedStatement ref = impl.get(sql);
               if ( ref == statement ) {
                  // remove will trigger releasePreparedStatement()
                  impl.remove(sql);
               } else {
                  nativeFinalizeStatement(mConnectionPtr, statementPtr);
               }
               throw ex;
            }
            return statement;
         }
      }

      /**
       * Caller MUST hold the mConnectionPtrMutex before calling this method
       *
       * @param statement
       */
      void releasePreparedStatement(PreparedStatement statement) {
         synchronized (impl) {
            PreparedStatement existing = impl.get(statement.mSql);
            if (existing == statement) {
               try {
                  if ( mConnectionPtr != 0L && statement.mStatementPtr != 0L ) {
                     nativeResetStatementAndClearBindings(mConnectionPtr, statement.mStatementPtr);
                  }
                  statement.mInUse = false;
               } catch (Throwable t) {
                  // The statement could not be reset due to an error.  Remove it from the cache.
                  // When remove() is called, the cache will invoke its entryRemoved() callback,
                  // which will in turn call finalizePreparedStatement() to finalize and
                  // recycle the statement.
                  getLogger().d(TAG, "Could not reset prepared statement due to an exception.  "
                      + "Removing it from the cache.  SQL: "
                      + AppNameSharedStateContainer.trimSqlForDisplay(statement.mSql));
                  getLogger().printStackTrace(t);
                  // remove will trigger releasePreparedStatement() [again]
                  impl.remove(statement.mSql);
               }
            } else {
               try {
                  if ( mConnectionPtr != 0L && statement.mStatementPtr != 0L ) {
                     nativeFinalizeStatement(mConnectionPtr, statement.mStatementPtr);
                  }
               } catch (Throwable t) {
                  // If we are finalizing, we really don't care what the error is.
                  getLogger().d(TAG, "Could not finalize prepared statement due to an exception.  "
                      + "SQL: " + AppNameSharedStateContainer.trimSqlForDisplay(statement.mSql));
                  getLogger().printStackTrace(t);
               } finally {
                  recyclePreparedStatement(statement);
               }
            }
         }
      }

      /**
       * Caller must hold mConnectionPtrMutex before calling this method.
       * Constructs a statement from scratch or from the re-use pool.
       * No interaction with LRU cache.
       *
       * @param sql
       * @param statementPtr
       * @param numParameters
       * @param type
       * @param readOnly
       * @return
       */
      private PreparedStatement obtainPreparedStatement(String sql, long statementPtr,
          int numParameters, int type, boolean readOnly) {
         synchronized (impl) {
            PreparedStatement statement = mPreparedStatementPool;
            if (statement != null) {
               mPreparedStatementPool = statement.mPoolNext;
               statement.mPoolNext = null;
            } else {
               statement = new PreparedStatement();
            }
            statement.mSql = sql;
            statement.mStatementPtr = statementPtr;
            statement.mNumParameters = numParameters;
            statement.mType = type;
            statement.mReadOnly = readOnly;
            statement.mInUse = true;
            return statement;
         }
      }

      /**
       * Puts the returned statement on the re-use pool.
       * No interaction with LRU cache.
       *
       * @param statement
       */
      private void recyclePreparedStatement(PreparedStatement statement) {
         synchronized (impl) {
            statement.mSql = null;
            statement.mPoolNext = mPreparedStatementPool;
            mPreparedStatementPool = statement;
         }
      }

      private boolean isCacheable(int statementType) {
         if (statementType == DatabaseUtils.STATEMENT_UPDATE || statementType == DatabaseUtils.STATEMENT_SELECT) {
            return true;
         }
         return false;
      }
   }
}
