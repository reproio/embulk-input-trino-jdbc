package com.reproio.embulk.input.trino;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class RunningQueryCancellerTest {

  @Test
  void cancelsRunningStatement() {
    AtomicInteger cancelCount = new AtomicInteger();
    Statement statement = fakeStatement(cancelCount::incrementAndGet);
    RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofSeconds(5));

    canceller.start(statement);
    canceller.cancelRunningQuery();

    assertEquals(1, cancelCount.get());
  }

  @Test
  void cancelsOnlyOnce() {
    AtomicInteger cancelCount = new AtomicInteger();
    Statement statement = fakeStatement(cancelCount::incrementAndGet);
    RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofSeconds(5));

    canceller.start(statement);
    canceller.cancelRunningQuery();
    canceller.cancelRunningQuery();

    assertEquals(1, cancelCount.get());
  }

  @Test
  void doesNothingWithoutStatement() {
    RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofSeconds(5));

    assertDoesNotThrow(canceller::cancelRunningQuery);
  }

  @Test
  void doesNothingAfterFinish() {
    AtomicInteger cancelCount = new AtomicInteger();
    Statement statement = fakeStatement(cancelCount::incrementAndGet);
    RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofSeconds(5));

    canceller.start(statement);
    canceller.finish(statement);
    canceller.cancelRunningQuery();

    assertEquals(0, cancelCount.get());
  }

  @Test
  void finishOfAnotherStatementDoesNotClearRunningStatement() {
    AtomicInteger cancelCount = new AtomicInteger();
    Statement statement = fakeStatement(cancelCount::incrementAndGet);
    Statement another = fakeStatement(() -> {});
    RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofSeconds(5));

    canceller.start(statement);
    canceller.finish(another);
    canceller.cancelRunningQuery();

    assertEquals(1, cancelCount.get());
  }

  @Test
  void swallowsExceptionFromCancel() {
    Statement statement =
        fakeStatement(
            () -> {
              throw new SQLException("boom");
            });
    RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofSeconds(5));

    canceller.start(statement);

    assertDoesNotThrow(canceller::cancelRunningQuery);
  }

  @Test
  void swallowsRuntimeExceptionFromCancel() {
    Statement statement =
        fakeStatement(
            () -> {
              throw new IllegalStateException("boom");
            });
    RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofSeconds(5));

    canceller.start(statement);

    assertDoesNotThrow(canceller::cancelRunningQuery);
  }

  @Test
  void givesUpWaitingAfterTimeout() {
    CountDownLatch release = new CountDownLatch(1);
    Statement statement = fakeStatement(release::await);
    RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofMillis(100));

    canceller.start(statement);
    long start = System.nanoTime();
    canceller.cancelRunningQuery();
    long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
    release.countDown();

    assertTrue(elapsed < 5000, "cancelRunningQuery took " + elapsed + " ms");
  }

  @Test
  void refusesExecutionAfterCancel() {
    RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofSeconds(5));

    canceller.cancelRunningQuery();

    assertThrows(SQLException.class, canceller::beforeExecute);
  }

  @Test
  void retriesCancelUntilExecutionFinishes() throws Exception {
    AtomicBoolean executionFinished = new AtomicBoolean();
    AtomicInteger cancelCount = new AtomicInteger();
    AtomicInteger cancelCountAfterExecution = new AtomicInteger();
    CountDownLatch cancelledWhileExecuting = new CountDownLatch(1);
    Statement statement =
        fakeStatement(
            () -> {
              cancelCount.incrementAndGet();
              if (executionFinished.get()) {
                cancelCountAfterExecution.incrementAndGet();
              } else {
                cancelledWhileExecuting.countDown();
              }
            });
    RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofSeconds(5));

    canceller.start(statement);
    canceller.beforeExecute();
    Thread hook = new Thread(canceller::cancelRunningQuery);
    hook.start();
    assertTrue(cancelledWhileExecuting.await(5, TimeUnit.SECONDS));
    executionFinished.set(true);
    canceller.afterExecute();
    hook.join(5000);

    assertFalse(hook.isAlive());
    assertTrue(cancelCount.get() >= 2, "cancel was called " + cancelCount.get() + " times");
    assertTrue(cancelCountAfterExecution.get() >= 1, "cancel was not called after execution");
    assertTrue(canceller.isCancelled());
  }

  @Test
  void stopsRetryingWhenStatementIsClosed() {
    AtomicInteger cancelCount = new AtomicInteger();
    Statement statement =
        fakeStatement(
            () -> {
              cancelCount.incrementAndGet();
              throw new SQLException("Statement is closed");
            },
            () -> true);
    RunningQueryCanceller canceller = new RunningQueryCanceller(Duration.ofSeconds(5));

    canceller.start(statement);
    assertDoesNotThrow(canceller::beforeExecute);
    canceller.cancelRunningQuery();

    assertEquals(1, cancelCount.get());
  }

  @FunctionalInterface
  private interface CancelAction {
    void run() throws Exception;
  }

  private static Statement fakeStatement(CancelAction onCancel) {
    return fakeStatement(onCancel, () -> false);
  }

  private static Statement fakeStatement(CancelAction onCancel, BooleanSupplier isClosed) {
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
                  return isClosed.getAsBoolean();
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
}
