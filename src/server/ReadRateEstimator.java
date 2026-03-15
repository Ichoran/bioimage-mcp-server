package lab.kerrr.mcpbio.bioimageserver;

import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.regression.SimpleRegression;

/**
 * Estimates per-plane read time using online linear regression, and
 * predicts whether reading the next batch of planes would exceed a
 * time budget at a given confidence level.
 *
 * <p>The model is: cumulative_time = intercept + slope × plane_count.
 * The intercept absorbs JVM warmup and reader initialization costs;
 * the slope is the steady-state per-plane rate.  After at least
 * {@value #MIN_OBSERVATIONS} data points, the estimator can produce
 * a conservative (upper confidence bound) prediction.
 *
 * <p>Byte budget is handled separately — since each plane has a known
 * fixed size, bytes are checked arithmetically, not estimated.
 *
 * <p>Usage:
 * <pre>
 *     var est = new ReadRateEstimator(timeoutNanos, 0.90);
 *     est.recordStep(planesRead, elapsedNanos);
 *     ...
 *     if (est.canAfford(nextBatchPlanes)) { ... }
 * </pre>
 *
 * <p>Not thread-safe.
 */
final class ReadRateEstimator {

    /**
     * Minimum number of recorded steps before we trust the estimate.
     * With fewer points, {@link #canAfford} always returns true
     * (optimistic — keep reading until we have data).
     */
    static final int MIN_OBSERVATIONS = 2;

    private final long budgetNanos;
    private final double confidence;
    private final SimpleRegression regression;
    private int totalPlanesRead;
    private long totalElapsedNanos;
    private int observationCount;

    /**
     * @param budgetNanos  total wall-clock nanoseconds allowed
     * @param confidence   confidence level for the upper bound (e.g. 0.90)
     */
    ReadRateEstimator(long budgetNanos, double confidence) {
        if (budgetNanos <= 0) {
            throw new IllegalArgumentException("budgetNanos must be positive");
        }
        if (confidence <= 0 || confidence >= 1) {
            throw new IllegalArgumentException(
                    "confidence must be in (0, 1), got " + confidence);
        }
        this.budgetNanos = budgetNanos;
        this.confidence = confidence;
        this.regression = new SimpleRegression(true);  // with intercept
    }

    /**
     * Record that a batch of planes was read.
     *
     * @param planes        number of planes read in this step
     * @param elapsedNanos  cumulative elapsed time from the start
     *                      of reading (not just this step)
     */
    void recordStep(int planes, long elapsedNanos) {
        totalPlanesRead += planes;
        totalElapsedNanos = elapsedNanos;
        observationCount++;
        regression.addData(totalPlanesRead, elapsedNanos);
    }

    /**
     * Returns true if the estimator predicts that reading
     * {@code additionalPlanes} more planes will stay within the
     * time budget at the configured confidence level.
     *
     * <p>If fewer than {@link #MIN_OBSERVATIONS} data points have
     * been recorded, returns true (optimistic — we need more data).
     *
     * @param additionalPlanes number of planes in the next batch
     */
    boolean canAfford(int additionalPlanes) {
        if (observationCount < MIN_OBSERVATIONS) {
            return true;  // not enough data — be optimistic
        }

        double predictedTotal = upperBoundTime(
                totalPlanesRead + additionalPlanes);
        return predictedTotal <= budgetNanos;
    }

    /**
     * Upper confidence bound on cumulative time at the given plane
     * count, using the regression's prediction interval.
     *
     * <p>The prediction interval for a new observation at x is:
     * <pre>
     *     ŷ ± t_{α, n-2} × s × √(1 + 1/n + (x - x̄)² / Sxx)
     * </pre>
     * We return the upper bound (ŷ + margin).
     */
    private double upperBoundTime(int atPlaneCount) {
        double predicted = regression.predict(atPlaneCount);

        // Degrees of freedom for simple linear regression: n - 2
        int df = observationCount - 2;
        if (df < 1) {
            // With exactly 2 points the line is exact — no interval.
            // Be slightly conservative: add 10% margin.
            return predicted * 1.1;
        }

        var tDist = new TDistribution(df);
        double tValue = tDist.inverseCumulativeProbability(confidence);

        // Residual standard error
        double mse = regression.getMeanSquareError();
        if (mse <= 0) {
            // Perfect fit (unlikely with real data) — trust the prediction
            return predicted;
        }
        double se = Math.sqrt(mse);

        // Prediction interval half-width
        double xBar = (double) (totalPlanesRead + 1) / 2.0;  // mean of 1..n
        double sxx = regression.getXSumSquares();
        double leverage = 1.0 + 1.0 / observationCount
                + Math.pow(atPlaneCount - xBar, 2) / sxx;
        double margin = tValue * se * Math.sqrt(leverage);

        return predicted + margin;
    }

    /** Number of planes read so far. */
    int totalPlanesRead() { return totalPlanesRead; }

    /** Number of steps recorded so far. */
    int observationCount() { return observationCount; }

    /** Total elapsed nanos recorded so far. */
    long totalElapsedNanos() { return totalElapsedNanos; }
}
