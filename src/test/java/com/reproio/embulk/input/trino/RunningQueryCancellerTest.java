package com.reproio.embulk.input.trino;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RunningQueryCancellerTest {
  private final RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofSeconds(5));
  private final AtomicInteger cancelCount = new AtomicInteger();

  @Test
  void cancelsRunningStatement() {
    canceller.track(countingStatement());
    canceller.cancelRunningQuery();

    assertEquals(1, cancelCount.get());
  }

  @Test
  void cancelsOnlyOnce() {
    canceller.track(countingStatement());
    canceller.cancelRunningQuery();
    canceller.cancelRunningQuery();

    assertEquals(1, cancelCount.get());
  }

  @Test
  void doesNothingWithoutStatement() {
    assertDoesNotThrow(canceller::cancelRunningQuery);
  }

  @Test
  void doesNothingAfterUntrack() {
    Statement statement = countingStatement();

    canceller.track(statement);
    canceller.untrack(statement);
    canceller.cancelRunningQuery();

    assertEquals(0, cancelCount.get());
  }

  @Test
  void untrackOfAnotherStatementKeepsTrackedStatement() {
    canceller.track(countingStatement());
    canceller.untrack(openStatement(() -> {}));
    canceller.cancelRunningQuery();

    assertEquals(1, cancelCount.get());
  }

  static Stream<Exception> cancelErrors() {
    return Stream.of(new SQLException("boom"), new IllegalStateException("boom"));
  }

  @ParameterizedTest
  @MethodSource("cancelErrors")
  void swallowsExceptionFromCancel(Exception error) {
    canceller.track(
        openStatement(
            () -> {
              throw error;
            }));

    assertDoesNotThrow(canceller::cancelRunningQuery);
  }

  @Test
  void givesUpWaitingAfterTimeout() {
    CountDownLatch release = new CountDownLatch(1);
    RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofMillis(100));

    canceller.track(openStatement(release::await));
    try {
      assertTimeoutPreemptively(Duration.ofSeconds(5), canceller::cancelRunningQuery);
    } finally {
      release.countDown();
    }
  }

  @Test
  void refusesExecutionAfterCancel() {
    AtomicInteger executeCount = new AtomicInteger();

    canceller.cancelRunningQuery();

    assertThrows(
        SQLException.class,
        () ->
            canceller.execute(
                () -> {
                  executeCount.incrementAndGet();
                  return null;
                }));
    assertEquals(0, executeCount.get());
  }

  @Test
  void returnsResultSetWithoutCancel() throws SQLException {
    ResultSet rs = fakeResultSet(new AtomicInteger());

    assertSame(rs, canceller.execute(() -> rs));
  }

  @Test
  void cancelsAgainAndClosesResultSetAfterExecutionFinishes() throws Exception {
    CountDownLatch cancelledWhileExecuting = new CountDownLatch(1);
    canceller.track(
        openStatement(
            () -> {
              cancelCount.incrementAndGet();
              cancelledWhileExecuting.countDown();
            }));
    AtomicInteger closeCount = new AtomicInteger();
    AtomicInteger cancelCountAtExecutionEnd = new AtomicInteger();
    CompletableFuture<Void> cancelFinished = new CompletableFuture<>();

    assertThrows(
        SQLException.class,
        () ->
            canceller.execute(
                () -> {
                  CompletableFuture.runAsync(canceller::cancelRunningQuery)
                      .whenComplete((r, e) -> cancelFinished.complete(null));
                  try {
                    assertTrue(cancelledWhileExecuting.await(5, TimeUnit.SECONDS));
                  } catch (InterruptedException e) {
                    throw new SQLException(e);
                  }
                  cancelCountAtExecutionEnd.set(cancelCount.get());
                  return fakeResultSet(closeCount);
                }));
    cancelFinished.get(5, TimeUnit.SECONDS);

    assertTrue(
        cancelCount.get() > cancelCountAtExecutionEnd.get(),
        () ->
            "cancel count: at execution end="
                + cancelCountAtExecutionEnd.get()
                + ", final="
                + cancelCount.get());
    assertEquals(1, closeCount.get());
  }

  @Test
  void stopsRetryingWhenStatementIsClosed() {
    canceller.track(
        closedStatement(
            () -> {
              cancelCount.incrementAndGet();
              throw new SQLException("Statement is closed");
            }));

    assertThrows(
        SQLException.class,
        () ->
            canceller.execute(
                () -> {
                  canceller.cancelRunningQuery();
                  return null;
                }));
    assertEquals(1, cancelCount.get());
  }

  @FunctionalInterface
  private interface CancelAction {
    void run() throws Exception;
  }

  private Statement countingStatement() {
    return openStatement(cancelCount::incrementAndGet);
  }

  private static Statement openStatement(CancelAction onCancel) {
    return fakeStatement(onCancel, false);
  }

  // Statement#cancel() of a closed statement throws, and isClosed() returns true.
  private static Statement closedStatement(CancelAction onCancel) {
    return fakeStatement(onCancel, true);
  }

  private static Statement fakeStatement(CancelAction onCancel, boolean closed) {
    return (Statement)
        Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "cancel":
                  onCancel.run();
                  return null;
                case "isClosed":
                  return closed;
                case "equals":
                  return proxy == args[0];
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "toString":
                  return "FakeStatement";
                default:
                  throw new UnsupportedOperationException(method.getName());
              }
            });
  }

  private static ResultSet fakeResultSet(AtomicInteger closeCount) {
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "close":
                  closeCount.incrementAndGet();
                  return null;
                case "equals":
                  return proxy == args[0];
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "toString":
                  return "FakeResultSet";
                default:
                  throw new UnsupportedOperationException(method.getName());
              }
            });
  }
}
