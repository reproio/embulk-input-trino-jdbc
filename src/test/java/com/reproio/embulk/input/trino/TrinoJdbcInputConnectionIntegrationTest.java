package com.reproio.embulk.input.trino;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.embulk.input.jdbc.JdbcInputConnection.BatchSelect;
import org.embulk.input.jdbc.JdbcInputConnection.PreparedQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.trino.TrinoContainer;
import org.testcontainers.utility.DockerImageName;

class TrinoJdbcInputConnectionIntegrationTest {
  private static TrinoContainer trino;

  @BeforeAll
  static void startTrino() throws InterruptedException {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    trino = new TrinoContainer(DockerImageName.parse("trinodb/trino:476"));
    trino.start();
    waitForWorker(Duration.ofSeconds(60));
  }

  // The container is regarded as started before the worker is registered, and queries that scan
  // tables fail with "nodes is empty" until then.
  private static void waitForWorker(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      try (Connection con = connect();
          ResultSet rs =
              con.createStatement()
                  .executeQuery("SELECT sum(extendedprice) FROM tpch.tiny.lineitem")) {
        rs.next();
        return;
      } catch (SQLException e) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("Trino worker did not become available in " + timeout, e);
        }
        Thread.sleep(500);
      }
    }
  }

  @AfterAll
  static void stopTrino() {
    if (trino != null) {
      trino.stop();
    }
  }

  @Test
  void cancelsRunningQuery() throws Exception {
    // Generating sf1000 lineitem takes far longer than this test. Use sum() instead of count(*)
    // because count(*) is answered from the table statistics without scanning.
    String query = "SELECT sum(extendedprice) FROM tpch.sf1000.lineitem";

    try (Connection monitor = connect();
        TrinoJdbcInputConnection con = new TrinoJdbcInputConnection(connect());
        BatchSelect cursor =
            con.newSelectCursor(new PreparedQuery(query, List.of()), List.of(), 1000, 0)) {
      CompletableFuture<Void> fetching =
          CompletableFuture.runAsync(
              () -> {
                try (ResultSet rs = cursor.fetch()) {
                  rs.next();
                } catch (SQLException e) {
                  throw new RuntimeException(e);
                }
              });

      // The query ID first points to START TRANSACTION, which is run by the same statement.
      String queryId;
      try {
        queryId =
            waitForQueryState(monitor, con::getRunningQueryId, "RUNNING", Duration.ofSeconds(60));
      } catch (AssertionError e) {
        if (fetching.isCompletedExceptionally()) {
          fetching.exceptionally(
              t -> {
                e.addSuppressed(t);
                return null;
              });
        }
        throw e;
      }

      long start = System.nanoTime();
      con.cancelRunningQuery();

      waitForQueryState(monitor, () -> queryId, "FAILED", Duration.ofSeconds(10));
      assertEquals("USER_CANCELED", findQuery(monitor, queryId).get().errorCode);
      ExecutionException e =
          assertThrows(ExecutionException.class, () -> fetching.get(10, TimeUnit.SECONDS));
      System.out.printf(
          "Cancelled %s in %d ms: %s%n",
          queryId, Duration.ofNanos(System.nanoTime() - start).toMillis(), e.getCause());
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(trino.getJdbcUrl(), "test", null);
  }

  private static String waitForQueryState(
      Connection monitor, Supplier<String> queryId, String expectedState, Duration timeout)
      throws SQLException, InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    String id = null;
    Optional<QueryState> last = Optional.empty();
    while (System.nanoTime() < deadline) {
      id = queryId.get();
      if (id != null) {
        last = findQuery(monitor, id);
        if (last.isPresent() && last.get().state.equals(expectedState)) {
          return id;
        }
      }
      Thread.sleep(200);
    }
    throw new AssertionError(
        "Query " + id + " did not become " + expectedState + " in " + timeout + ": " + last);
  }

  private static Optional<QueryState> findQuery(Connection monitor, String queryId)
      throws SQLException {
    String sql = "SELECT state, error_code FROM system.runtime.queries WHERE query_id = ?";
    try (PreparedStatement stmt = monitor.prepareStatement(sql)) {
      stmt.setString(1, queryId);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(new QueryState(rs.getString("state"), rs.getString("error_code")));
      }
    }
  }

  private static class QueryState {
    final String state;
    final String errorCode;

    QueryState(String state, String errorCode) {
      this.state = state;
      this.errorCode = errorCode;
    }

    @Override
    public String toString() {
      return "state=" + state + ", error_code=" + errorCode;
    }
  }
}
