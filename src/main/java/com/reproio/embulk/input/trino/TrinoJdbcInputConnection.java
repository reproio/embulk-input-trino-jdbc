package com.reproio.embulk.input.trino;

import io.trino.jdbc.TrinoStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.embulk.input.jdbc.JdbcInputConnection;
import org.embulk.input.jdbc.JdbcLiteral;
import org.embulk.input.jdbc.getter.ColumnGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cancels the running Trino query when the JVM shuts down (e.g. on SIGTERM).
 *
 * <p>Embulk does not stop plugins gracefully on SIGTERM, so without this the query keeps running on
 * the Trino server until it reaches query_max_run_time.
 */
public class TrinoJdbcInputConnection extends JdbcInputConnection {
  private static final Logger logger = LoggerFactory.getLogger(TrinoJdbcInputConnection.class);

  // ECS sends SIGKILL 30 seconds (stopTimeout) after SIGTERM by default.
  private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(10);

  private final RunningQueryCanceller canceller = new RunningQueryCanceller(CANCEL_TIMEOUT);
  private final Thread shutdownHook;

  public TrinoJdbcInputConnection(Connection connection) throws SQLException {
    super(connection, null);
    shutdownHook = new Thread(canceller::cancelRunningQuery, "trino-query-cancel-hook");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  // Same as JdbcInputConnection#newBatchSelect in embulk-input-jdbc 0.13.2, except that the
  // statement is tracked so that it can be cancelled.
  @Override
  protected BatchSelect newBatchSelect(
      PreparedQuery preparedQuery, List<ColumnGetter> getters, int fetchRows, int queryTimeout)
      throws SQLException {
    String query = preparedQuery.getQuery();
    List<JdbcLiteral> params = preparedQuery.getParameters();

    PreparedStatement stmt = connection.prepareStatement(query);
    stmt.setFetchSize(fetchRows);
    stmt.setQueryTimeout(queryTimeout);
    logger.info("SQL: " + query);
    if (!params.isEmpty()) {
      logger.info("Parameters: {}", params);
      prepareParameters(stmt, getters, params);
    }
    if (stmt.isWrapperFor(TrinoStatement.class)) {
      stmt.unwrap(TrinoStatement.class)
          .setProgressMonitor(stats -> canceller.setQueryId(stats.getQueryId()));
    }
    canceller.start(stmt);
    return new CancellableSingleSelect(stmt);
  }

  // Does the same as the shutdown hook. Visible for testing.
  void cancelRunningQuery() {
    canceller.cancelRunningQuery();
  }

  // Visible for testing.
  String getRunningQueryId() {
    return canceller.getQueryId();
  }

  @Override
  public void close() throws SQLException {
    try {
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    } catch (IllegalStateException e) {
      // The JVM is already shutting down. The hook is running or will run.
    }
    super.close();
  }

  private class CancellableSingleSelect extends SingleSelect {
    private final PreparedStatement fetchStatement;

    CancellableSingleSelect(PreparedStatement fetchStatement) {
      super(fetchStatement);
      this.fetchStatement = fetchStatement;
    }

    @Override
    public void close() throws SQLException {
      canceller.finish(fetchStatement);
      fetchStatement.close();
    }
  }
}
