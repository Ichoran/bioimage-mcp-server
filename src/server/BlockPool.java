package lab.kerrr.mcpbio.bioimageserver;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * The server-wide pool that compresses and writes OME-Zarr shard blocks.
 *
 * <p>This is owned by {@link BioImageService} as a <b>single shared resource</b>
 * (one per server, sized to {@code --parallelism}).  Every {@code export_to_ngff}
 * from every client/transport submits into it, so total concurrent compression
 * is capped at {@code threads} no matter how many clients connect — the machine
 * does not grow with clients.
 *
 * <p>Backpressure is a shared {@link Semaphore}: {@link #submit} blocks until the
 * in-flight budget (queued + running) has a slot, bounding total memory
 * server-wide.  Each task holds one shard block (~1–4 MB), so memory ≈
 * {@code permits × shardBytes}.
 */
final class BlockPool implements AutoCloseable {

    private final ExecutorService pool;
    private final Semaphore gate;
    private final int threads;

    BlockPool(int threads) {
        this.threads = Math.max(1, threads);
        this.pool = Executors.newFixedThreadPool(this.threads, r -> {
            Thread t = new Thread(r, "ngff-writer");
            t.setDaemon(true);   // never block JVM exit
            return t;
        });
        // A little queue ahead of the workers lets readers stay a step ahead
        // without unbounded buffering.
        this.gate = new Semaphore(Math.max(2, this.threads * 2));
    }

    int threads() {
        return threads;
    }

    /**
     * Submit a task with backpressure: blocks until a shared in-flight slot is
     * free, runs the task on the pool, and releases the slot when it finishes.
     *
     * @throws InterruptedException if interrupted while waiting for a slot
     */
    Future<?> submit(Runnable task) throws InterruptedException {
        gate.acquire();
        try {
            return pool.submit(() -> {
                try { task.run(); }
                finally { gate.release(); }
            });
        } catch (RuntimeException e) {   // e.g. RejectedExecutionException
            gate.release();
            throw e;
        }
    }

    /**
     * Wait for every future to finish, swallowing interrupts (the caller may
     * be a cancelled export that still must let in-flight writes drain before
     * deleting the partial store) and restoring the interrupt flag afterward.
     * Task failures are captured out-of-band (the task records them); here we
     * only ensure completion.
     */
    static void awaitAll(List<Future<?>> futures) {
        boolean interrupted = false;
        for (Future<?> f : futures) {
            while (true) {
                try {
                    f.get();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;   // keep waiting; restore flag at end
                } catch (java.util.concurrent.ExecutionException e) {
                    break;                // tasks capture their own errors
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
