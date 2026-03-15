package lab.kerrr.mcpbio.bioimageserver;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a unit of work on a virtual thread with a timeout, then attempts
 * to cancel via interrupt with exponential backoff if the timeout expires.
 *
 * <p>Some blocking operations (particularly certain I/O paths in native
 * libraries like Bio-Formats) do not respond to a single interrupt.
 * Repeated interrupts with increasing delays between attempts can
 * sometimes break through.  This is the "spam interrupt" strategy.
 *
 * <p>If the work thread never terminates despite all interrupt attempts,
 * the result reports {@code threadStillAlive = true}.  Virtual threads
 * are cheap, so an occasional orphan is acceptable — but callers should
 * log and monitor for this condition.
 *
 * <p>Usage (blocking):
 * <pre>{@code
 * var task = new CancellableTask(Duration.ofSeconds(30));
 * var result = task.run(() -> readLargeImage(path));
 * switch (result) {
 *     case CancellableTask.Result.Completed<Image> c -> use(c.value());
 *     case CancellableTask.Result.Failed<Image> f    -> report(f.error());
 *     case CancellableTask.Result.TimedOut<Image> t   -> reportTimeout(t);
 * }
 * }</pre>
 *
 * <p>Usage (with early cancellation):
 * <pre>{@code
 * var task = new CancellableTask(Duration.ofSeconds(30));
 * var handle = task.start(() -> readLargeImage(path));
 * // ... later, from another thread or after another task fails:
 * handle.cancel();
 * var result = handle.await();
 * }</pre>
 *
 * @param timeout                  maximum wall-clock time for the work to complete
 *                                 before interrupt attempts begin
 * @param initialInterruptInterval delay after the first interrupt before
 *                                 retrying; doubled on each subsequent attempt,
 *                                 capped at 1 second
 * @param maxInterrupts            maximum number of interrupt attempts before
 *                                 giving up
 */
public record CancellableTask(
        Duration timeout,
        Duration initialInterruptInterval,
        int maxInterrupts) {

    private static final long MAX_BACKOFF_MS = 1000;

    public CancellableTask {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (initialInterruptInterval.isNegative() || initialInterruptInterval.isZero()) {
            throw new IllegalArgumentException("initialInterruptInterval must be positive");
        }
        if (maxInterrupts < 1) {
            throw new IllegalArgumentException("maxInterrupts must be at least 1");
        }
    }

    /** Creates a task with default interrupt backoff (10 ms initial, up to 10 attempts). */
    public CancellableTask(Duration timeout) {
        this(timeout, Duration.ofMillis(10), 10);
    }

    /**
     * The outcome of running a task.
     *
     * @param <T> the type of value produced on success
     */
    public sealed interface Result<T> {
        /** The work completed (possibly after being interrupted) and produced a value. */
        record Completed<T>(T value) implements Result<T> {}

        /** The work threw an exception.  Includes {@link InterruptedException} if
         *  the work responded to an interrupt by throwing. */
        record Failed<T>(Throwable error) implements Result<T> {}

        /**
         * The work did not complete within the timeout and did not respond
         * to interrupt attempts.
         *
         * @param elapsed          total wall-clock time spent (timeout + interrupt attempts)
         * @param interruptsSent   how many interrupts were delivered
         * @param threadStillAlive whether the work thread was still alive when we gave up
         */
        record TimedOut<T>(Duration elapsed, int interruptsSent,
                           boolean threadStillAlive) implements Result<T> {}
    }

    /**
     * A handle to a running task that supports early cancellation and
     * blocking retrieval of the result.
     *
     * @param <T> the type of value the task produces
     */
    public static final class Handle<T> {
        private final Thread workThread;
        private final AtomicReference<T> resultRef;
        private final AtomicReference<Throwable> errorRef;
        private final AtomicBoolean completed;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private final long startNanos;
        private final CancellableTask config;

        private Handle(Thread workThread,
                       AtomicReference<T> resultRef,
                       AtomicReference<Throwable> errorRef,
                       AtomicBoolean completed,
                       long startNanos,
                       CancellableTask config) {
            this.workThread = workThread;
            this.resultRef = resultRef;
            this.errorRef = errorRef;
            this.completed = completed;
            this.startNanos = startNanos;
            this.config = config;
        }

        /**
         * Requests early cancellation of the task.  This triggers the
         * same interrupt-with-backoff sequence that a timeout would,
         * but starts immediately.
         *
         * <p>This method is non-blocking — it sets a flag that {@link #await()}
         * checks.  If {@code await()} is already waiting for the primary
         * timeout, it will not notice the cancellation until that timeout
         * expires.  If you need immediate cancellation, call {@code cancel()}
         * from a thread that is <em>not</em> the one blocked in
         * {@code await()}, and use a separate mechanism (e.g. interrupting
         * the awaiting thread) to unblock it.
         *
         * <p>Calling {@code cancel()} after the task has already completed
         * has no effect.
         */
        public void cancel() {
            cancelRequested.set(true);
            // Give the work thread an immediate interrupt so it doesn't
            // have to wait for the timeout to expire before noticing.
            workThread.interrupt();
        }

        /** Returns true if {@link #cancel()} has been called. */
        public boolean isCancelRequested() {
            return cancelRequested.get();
        }

        /**
         * Blocks until the task completes, fails, times out, or is
         * cancelled, then returns the result.
         *
         * <p>If {@link #cancel()} was called before or during the primary
         * wait, the interrupt-with-backoff sequence runs immediately
         * (skipping the remaining timeout).
         */
        public Result<T> await() {
            // Wait for the primary timeout, but check for early cancellation.
            if (!cancelRequested.get()) {
                try {
                    workThread.join(config.timeout);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    workThread.interrupt();
                    return new Result.Failed<>(e);
                }
            }

            if (!workThread.isAlive()) {
                return buildResult(resultRef, errorRef, completed);
            }

            // Either timed out or cancel was requested — interrupt with backoff.
            long intervalMs = config.initialInterruptInterval.toMillis();
            int sent = 0;
            for (int i = 0; i < config.maxInterrupts && workThread.isAlive(); i++) {
                workThread.interrupt();
                sent++;
                try {
                    workThread.join(Duration.ofMillis(intervalMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    workThread.interrupt();
                    return new Result.Failed<>(e);
                }
                intervalMs = Math.min(intervalMs * 2, MAX_BACKOFF_MS);
            }

            if (!workThread.isAlive()) {
                return buildResult(resultRef, errorRef, completed);
            }

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            return new Result.TimedOut<>(elapsed, sent, true);
        }
    }

    /**
     * Starts the work on a virtual thread and returns a {@link Handle}
     * that can be used to await the result or request early cancellation.
     */
    public <T> Handle<T> start(Callable<T> work) {
        var resultRef = new AtomicReference<T>();
        var errorRef = new AtomicReference<Throwable>();
        var completed = new AtomicBoolean(false);

        long startNanos = System.nanoTime();

        var thread = Thread.ofVirtual()
                .name("cancellable-task")
                .start(() -> {
                    try {
                        T value = work.call();
                        resultRef.set(value);
                        completed.set(true);
                    } catch (Throwable t) {
                        errorRef.set(t);
                    }
                });

        return new Handle<>(thread, resultRef, errorRef, completed, startNanos, this);
    }

    /**
     * Convenience method: starts the work and immediately awaits the result.
     * Equivalent to {@code start(work).await()}.
     */
    public <T> Result<T> run(Callable<T> work) {
        return start(work).await();
    }

    private static <T> Result<T> buildResult(
            AtomicReference<T> resultRef,
            AtomicReference<Throwable> errorRef,
            AtomicBoolean completed) {
        var error = errorRef.get();
        if (error != null) {
            return new Result.Failed<>(error);
        }
        if (completed.get()) {
            return new Result.Completed<>(resultRef.get());
        }
        // Thread terminated without setting either flag — should not happen,
        // but must not be silently ignored (scientific software principle).
        return new Result.Failed<>(new IllegalStateException(
                "Task thread terminated without producing a result or error"));
    }
}
