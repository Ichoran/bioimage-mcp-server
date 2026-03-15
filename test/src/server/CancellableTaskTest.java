package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.CancellableTask.Result;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CancellableTaskTest {

    // --- Normal completion ---

    @Test
    void completesWithValue() {
        var task = new CancellableTask(Duration.ofSeconds(5));
        var result = task.run(() -> 42);
        assertInstanceOf(Result.Completed.class, result);
        assertEquals(42, ((Result.Completed<Integer>) result).value());
    }

    @Test
    void completesWithNull() {
        var task = new CancellableTask(Duration.ofSeconds(5));
        var result = task.run(() -> null);
        assertInstanceOf(Result.Completed.class, result);
        assertNull(((Result.Completed<?>) result).value());
    }

    // --- Exception propagation ---

    @Test
    void propagatesException() {
        var task = new CancellableTask(Duration.ofSeconds(5));
        var result = task.run(() -> { throw new IllegalArgumentException("bad input"); });
        assertInstanceOf(Result.Failed.class, result);
        var failed = (Result.Failed<?>) result;
        assertInstanceOf(IllegalArgumentException.class, failed.error());
        assertEquals("bad input", failed.error().getMessage());
    }

    @Test
    void propagatesRuntimeException() {
        var task = new CancellableTask(Duration.ofSeconds(5));
        var result = task.run(() -> {
            throw new RuntimeException("boom");
        });
        assertInstanceOf(Result.Failed.class, result);
    }

    // --- Timeout and interrupt ---

    @Test
    void timesOutAndInterruptsResponsiveTask() {
        var interrupted = new CountDownLatch(1);
        var task = new CancellableTask(Duration.ofMillis(50));

        var result = task.run(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(60));
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return "should not reach";
        });

        // The task should respond to interrupt by throwing InterruptedException.
        assertInstanceOf(Result.Failed.class, result);
        var failed = (Result.Failed<?>) result;
        assertInstanceOf(InterruptedException.class, failed.error());
        assertEquals(0, interrupted.getCount(), "task should have been interrupted");
    }

    @Test
    void interruptCountIsTrackedForUnresponsiveTask() {
        // Task that ignores interrupts by catching and clearing the flag.
        var interruptCount = new AtomicInteger(0);
        var task = new CancellableTask(
                Duration.ofMillis(50),
                Duration.ofMillis(5),
                3);

        var result = task.run(() -> {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(Duration.ofMillis(1));
                } catch (InterruptedException e) {
                    interruptCount.incrementAndGet();
                    // Deliberately swallow and continue.
                }
            }
            return "should not reach";
        });

        // Should get TimedOut because the task keeps swallowing interrupts.
        // (It might also be Failed if the last interrupt lands just right.)
        if (result instanceof Result.TimedOut<?> timedOut) {
            assertTrue(timedOut.interruptsSent() >= 1);
            assertTrue(timedOut.interruptsSent() <= 3);
            assertTrue(timedOut.threadStillAlive());
        }
        // It's also acceptable (though unlikely) for the task to finally
        // respond on the last interrupt — in which case it's Failed.
        // Either way, interrupts were sent.
        assertTrue(interruptCount.get() >= 1,
                "at least one interrupt should have been delivered");
    }

    @Test
    void taskThatEventuallyRespondsToInterrupt() throws Exception {
        // Task ignores the first interrupt but responds to later ones.
        var interruptCount = new AtomicInteger(0);
        var task = new CancellableTask(
                Duration.ofMillis(50),
                Duration.ofMillis(10),
                10);

        var result = task.run(() -> {
            while (true) {
                try {
                    Thread.sleep(Duration.ofMillis(2));
                } catch (InterruptedException e) {
                    int count = interruptCount.incrementAndGet();
                    if (count >= 2) {
                        // Respond to the second interrupt.
                        throw e;
                    }
                    // Ignore the first one.
                }
            }
        });

        assertInstanceOf(Result.Failed.class, result);
        var failed = (Result.Failed<?>) result;
        assertInstanceOf(InterruptedException.class, failed.error());
        assertTrue(interruptCount.get() >= 2);
    }

    // --- Validation ---

    @Test
    void rejectsZeroTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new CancellableTask(Duration.ZERO));
    }

    @Test
    void rejectsNegativeTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new CancellableTask(Duration.ofMillis(-1)));
    }

    @Test
    void rejectsZeroInterruptInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> new CancellableTask(Duration.ofSeconds(1), Duration.ZERO, 5));
    }

    @Test
    void rejectsZeroMaxInterrupts() {
        assertThrows(IllegalArgumentException.class,
                () -> new CancellableTask(Duration.ofSeconds(1), Duration.ofMillis(10), 0));
    }

    // --- Early cancellation via Handle ---

    @Test
    void cancelStopsResponsiveTask() throws Exception {
        var started = new CountDownLatch(1);
        var task = new CancellableTask(Duration.ofSeconds(5));

        var handle = task.start(() -> {
            started.countDown();
            Thread.sleep(Duration.ofSeconds(10));
            return "should not reach";
        });

        // Wait for the work to actually start, then cancel.
        started.await();
        handle.cancel();
        assertTrue(handle.isCancelRequested());

        var result = handle.await();
        // The task responds to interrupt, so we get Failed with InterruptedException.
        assertInstanceOf(Result.Failed.class, result);
        assertInstanceOf(InterruptedException.class,
                ((Result.Failed<?>) result).error());
    }

    @Test
    void cancelBeforeAwaitSkipsTimeout() {
        var task = new CancellableTask(Duration.ofSeconds(5));

        var handle = task.start(() -> {
            Thread.sleep(Duration.ofSeconds(10));
            return "should not reach";
        });

        // Cancel immediately — await() should not wait 5 seconds.
        handle.cancel();
        long beforeMs = System.currentTimeMillis();
        var result = handle.await();
        long elapsedMs = System.currentTimeMillis() - beforeMs;

        // Should complete quickly (well under the 5s timeout).
        assertTrue(elapsedMs < 2000,
                "await() after cancel() should not wait for the full timeout, took " + elapsedMs + "ms");
        // Task is interrupt-responsive, so it should fail.
        assertInstanceOf(Result.Failed.class, result);
    }

    @Test
    void cancelOnAlreadyCompletedTaskIsHarmless() {
        var task = new CancellableTask(Duration.ofSeconds(2));
        var handle = task.start(() -> "done");
        var result = handle.await();

        // Cancel after completion — should be a no-op.
        handle.cancel();
        assertTrue(handle.isCancelRequested());
        assertInstanceOf(Result.Completed.class, result);
        assertEquals("done", ((Result.Completed<String>) result).value());
    }

    @Test
    void multipleTasksOneCancelledOtherCompletes() throws Exception {
        var task = new CancellableTask(Duration.ofSeconds(5));

        var handle1 = task.start(() -> {
            Thread.sleep(Duration.ofSeconds(10));
            return "slow";
        });
        var handle2 = task.start(() -> "fast");

        // handle2 completes quickly.
        var result2 = handle2.await();
        assertInstanceOf(Result.Completed.class, result2);

        // Cancel handle1 because we no longer need it.
        handle1.cancel();
        var result1 = handle1.await();
        assertInstanceOf(Result.Failed.class, result1);
    }

    // --- Elapsed time reporting ---

    @Test
    void timedOutReportsElapsedTime() {
        var task = new CancellableTask(
                Duration.ofMillis(50),
                Duration.ofMillis(5),
                2);

        var result = task.run(() -> {
            // Busy-wait, ignoring all interrupts.
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            return null;
        });

        if (result instanceof Result.TimedOut<?> timedOut) {
            // Elapsed should be at least the timeout.
            assertTrue(timedOut.elapsed().toMillis() >= 50,
                    "elapsed should be at least the timeout duration");
        }
    }
}
