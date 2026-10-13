package com.reproio.embulk.input.trino;

import java.sql.ResultSet;
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

  void track(Statement statement) {
    queryId.set(null);
    runningStatement.set(statement);
  }

  void untrack(Statement statement) {
    runningStatement.compareAndSet(statement, null);
  }

  @FunctionalInterface
  interface QueryExecution {
    ResultSet execute() throws SQLException;
  }

  /**
   * Runs Statement#executeQuery() so that it cooperates with {@link #cancelRunningQuery()}.
   *
   * <p>Refuses to submit the query once the JVM has started shutting down, and closes the result
   * set if the shutdown starts while submitting. Otherwise the query submitted after the shutdown
   * hook keeps running on the Trino server.
   */
  ResultSet execute(QueryExecution execution) throws SQLException {
    // Set executing before reading cancelled, while cancelRunningQuery() sets cancelled before
    // cancelWithRetry() reads executing, so that at least one side notices the other.
    executing.set(true);
    try {
      if (cancelled.get()) {
        throw cancelledException(null);
      }
      ResultSet rs;
      try {
        rs = execution.execute();
      } catch (SQLException | RuntimeException e) {
        if (cancelled.get()) {
          throw cancelledException(e);
        }
        throw e;
      }
      if (cancelled.get()) {
        throw closeCancelledResultSet(rs);
      }
      return rs;
    } finally {
      executing.set(false);
    }
  }

  private static SQLException cancelledException(Throwable cause) {
    return new SQLException(
        "The Trino query was cancelled because the JVM is shutting down", cause);
  }

  // Closes the result set of the query submitted while the JVM started shutting down, and returns
  // the exception to throw instead of returning the result set.
  private static SQLException closeCancelledResultSet(ResultSet rs) {
    SQLException ex = cancelledException(null);
    if (rs != null) {
      try {
        rs.close();
      } catch (SQLException closeError) {
        ex.addSuppressed(closeError);
      }
    }
    return ex;
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
      long deadlineNanos = System.nanoTime() + timeout.toNanos();
      Thread thread =
          new Thread(() -> cancelWithRetry(statement, deadlineNanos), "trino-query-canceller");
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

  private void cancelWithRetry(Statement statement, long deadlineNanos) {
    try {
      // Statement#cancel() does nothing while executeQuery() is still submitting the query, so
      // retry until executeQuery() returns, and cancel once more to close the result set.
      do {
        boolean wasExecuting = executing.get();
        boolean stillOpen = cancelIfOpen(statement);
        if (!stillOpen || !wasExecuting) {
          logger.warn("Cancelled the Trino query (query_id: {})", queryId.get());
          return;
        }
        Thread.sleep(RETRY_INTERVAL_MILLIS);
      } while (System.nanoTime() - deadlineNanos < 0);
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
