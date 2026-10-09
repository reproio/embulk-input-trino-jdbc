package com.reproio.embulk.input.trino;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps track of the statement that is currently running and cancels it on demand.
 *
 * <p>{@link #cancelRunningQuery()} is intended to be called from a JVM shutdown hook, so it never
 * throws and returns within the given timeout even if the Trino server does not respond.
 */
class RunningQueryCanceller {
  private static final Logger logger = LoggerFactory.getLogger(RunningQueryCanceller.class);

  private static final long RETRY_INTERVAL_MILLIS = 100;

  private final AtomicReference<Statement> runningStatement = new AtomicReference<>();
  private final AtomicReference<String> queryId = new AtomicReference<>();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicBoolean executing = new AtomicBoolean();
  private final Duration timeout;

  RunningQueryCanceller(Duration timeout) {
    this.timeout = timeout;
  }

  void start(Statement statement) {
    queryId.set(null);
    runningStatement.set(statement);
  }

  void finish(Statement statement) {
    runningStatement.compareAndSet(statement, null);
  }

  // Called by the main thread right before Statement#executeQuery().
  void beforeExecute() throws SQLException {
    executing.set(true);
    if (cancelled.get()) {
      executing.set(false);
      throw cancelledException(null);
    }
  }

  // Called by the main thread right after Statement#executeQuery() returns or throws.
  void afterExecute() {
    executing.set(false);
  }

  boolean isCancelled() {
    return cancelled.get();
  }

  SQLException cancelledException(Throwable cause) {
    return new SQLException(
        "The Trino query was cancelled because the JVM is shutting down", cause);
  }

  // The ID may be the one of START TRANSACTION, which is run by the same statement right before
  // the query, if the query has not reported its progress yet.
  void setQueryId(String queryId) {
    this.queryId.set(queryId);
  }

  String getQueryId() {
    return queryId.get();
  }

  void cancelRunningQuery() {
    cancelled.set(true);
    Statement statement = runningStatement.getAndSet(null);
    if (statement == null) {
      return;
    }
    // Read the query ID every time it is logged because it is updated while retrying.
    try {
      logger.warn(
          "Cancelling the running Trino query (query_id: {}) because the JVM is shutting down",
          queryId.get());
      // Statement#cancel() sends DELETE to the Trino server synchronously. Run it in another
      // thread so that a slow server does not keep the JVM alive until SIGKILL.
      long deadline = System.nanoTime() + timeout.toNanos();
      Thread thread = new Thread(() -> cancel(statement, deadline), "trino-query-canceller");
      thread.setDaemon(true);
      thread.start();
      // join(0) waits forever.
      thread.join(Math.max(1, timeout.toMillis()));
      if (thread.isAlive()) {
        logger.warn(
            "Gave up waiting for cancelling the Trino query (query_id: {}) after {} ms",
            queryId.get(),
            timeout.toMillis());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.warn("Interrupted while cancelling the Trino query (query_id: {})", queryId.get());
    } catch (Throwable e) {
      logger.warn("Failed to cancel the Trino query (query_id: {})", queryId.get(), e);
    }
  }

  private void cancel(Statement statement, long deadline) {
    try {
      // Statement#cancel() does nothing while executeQuery() is still submitting the query, so
      // retry until executeQuery() returns, and cancel once more to close the result set.
      do {
        boolean wasExecuting = executing.get();
        if (!cancelIfOpen(statement) || !wasExecuting) {
          logger.warn("Cancelled the Trino query (query_id: {})", queryId.get());
          return;
        }
        Thread.sleep(RETRY_INTERVAL_MILLIS);
      } while (System.nanoTime() - deadline < 0);
      logger.warn(
          "Gave up cancelling the Trino query (query_id: {}) because it was still being submitted"
              + " after {} ms",
          queryId.get(),
          timeout.toMillis());
    } catch (Throwable e) {
      logger.warn("Failed to cancel the Trino query (query_id: {})", queryId.get(), e);
    }
  }

  // Returns false if the statement has already been closed, which also cancels the query.
  private static boolean cancelIfOpen(Statement statement) throws SQLException {
    try {
      statement.cancel();
      return true;
    } catch (SQLException e) {
      if (statement.isClosed()) {
        return false;
      }
      throw e;
    }
  }
}
