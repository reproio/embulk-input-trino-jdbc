package com.reproio.embulk.input.trino;

import java.sql.Statement;
import java.time.Duration;
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

  private final AtomicReference<Statement> runningStatement = new AtomicReference<>();
  private final AtomicReference<String> queryId = new AtomicReference<>();
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

  void setQueryId(String queryId) {
    this.queryId.set(queryId);
  }

  String getQueryId() {
    return queryId.get();
  }

  void cancelRunningQuery() {
    Statement statement = runningStatement.getAndSet(null);
    if (statement == null) {
      return;
    }
    String id = queryId.get();
    try {
      logger.warn(
          "Cancelling the running Trino query (query_id: {}) because the JVM is shutting down", id);
      // Statement#cancel() sends DELETE to the Trino server synchronously. Run it in another
      // thread so that a slow server does not keep the JVM alive until SIGKILL.
      Thread thread = new Thread(() -> cancel(statement, id), "trino-query-canceller");
      thread.setDaemon(true);
      thread.start();
      thread.join(timeout.toMillis());
      if (thread.isAlive()) {
        logger.warn(
            "Gave up waiting for cancelling the Trino query (query_id: {}) after {} ms",
            id,
            timeout.toMillis());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Throwable e) {
      logger.warn("Failed to cancel the Trino query (query_id: {})", id, e);
    }
  }

  private static void cancel(Statement statement, String id) {
    try {
      statement.cancel();
      logger.warn("Cancelled the Trino query (query_id: {})", id);
    } catch (Throwable e) {
      logger.warn("Failed to cancel the Trino query (query_id: {})", id, e);
    }
  }
}
