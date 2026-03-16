package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.CancellableTask.Result;
import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;

import java.io.IOException;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Computes per-channel intensity statistics for an image.
 *
 * <p>For each requested channel, reads the relevant planes and
 * accumulates min, max, mean, stddev, median, histogram, saturation
 * fractions, and bit-depth utilization.  Uses {@link StatsAccumulator}
 * for efficient streaming computation across multiple Z-slices and
 * timepoints.
 *
 * <p><b>Adaptive vs explicit reading</b></p>
 *
 * <p>When dimension ranges are explicitly specified, all requested
 * planes are read (with even subsampling if they exceed the byte
 * budget).  When ranges are left null (defaults), the tool reads
 * adaptively: it monitors per-plane read time via
 * {@link ReadRateEstimator} and stops when the 90th-percentile
 * prediction would exceed the time budget.  This prevents naive
 * default calls from hanging on huge files.
 */
public final class GetIntensityStatsTool {

    private GetIntensityStatsTool() {}

    /** Default number of histogram bins. */
    static final int DEFAULT_HISTOGRAM_BINS = 256;

    /** Confidence level for adaptive budget estimation. */
    static final double BUDGET_CONFIDENCE = 0.90;

    /**
     * An inclusive range of indices.  A null Range means "use the
     * default" (which varies by dimension — all channels, all Z,
     * timepoint 0).  A Range with start == end selects a single index.
     *
     * @param start first index (inclusive, zero-based)
     * @param end   last index (inclusive, zero-based)
     */
    public record Range(int start, int end) {
        public Range {
            if (start < 0) {
                throw new IllegalArgumentException(
                        "range start must be non-negative, got " + start);
            }
            if (end < start) {
                throw new IllegalArgumentException(
                        "range end (" + end + ") must be >= start ("
                        + start + ")");
            }
        }

        /** Convenience for a single index. */
        public static Range of(int index) { return new Range(index, index); }

        /** Convenience for a range. */
        public static Range of(int start, int end) {
            return new Range(start, end);
        }

        /** Number of indices in this range. */
        public int count() { return end - start + 1; }

        /** Expand to an array of indices. */
        int[] toArray() {
            var arr = new int[count()];
            for (int i = 0; i < arr.length; i++) arr[i] = start + i;
            return arr;
        }
    }

    /**
     * Parameters for a {@code get_intensity_stats} call.
     *
     * <p>Each dimension (channel, Z, T) accepts a nullable {@link Range}:
     * <ul>
     *   <li>{@code null} channels → all channels
     *   <li>{@code null} Z → all Z-slices (adaptive)
     *   <li>{@code null} T → all timepoints (adaptive)
     * </ul>
     *
     * <p>When Z and/or T are null, the tool reads adaptively: it
     * monitors read rate and stops before exceeding the time or byte
     * budget.  When explicit ranges are given, it reads all requested
     * planes (subsampling evenly if necessary to stay in byte budget).
     *
     * @param path           path to the image file
     * @param series         zero-based series index
     * @param channels       channel range, or null for all
     * @param zRange         Z-slice range, or null for adaptive
     * @param tRange         timepoint range, or null for adaptive
     * @param histogramBins  number of bins for the output histogram
     * @param timeout        wall-clock time limit
     * @param maxBytes       approximate cap on raw pixel bytes to read
     */
    public record Request(
            String path,
            int series,
            Range channels,
            Range zRange,
            Range tRange,
            int histogramBins,
            Duration timeout,
            long maxBytes) {

        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
        public static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;

        public Request {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("path must not be blank");
            }
            if (series < 0) {
                throw new IllegalArgumentException(
                        "series must be non-negative");
            }
            if (histogramBins < 1) {
                throw new IllegalArgumentException(
                        "histogramBins must be positive");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive");
            }
        }

        /** Full-featured factory with nullable parameters and defaults. */
        public static Request of(String path, Integer series,
                                  Range channels, Range zRange, Range tRange,
                                  Integer histogramBins,
                                  Duration timeout, Long maxBytes) {
            return new Request(
                    path,
                    series != null ? series : 0,
                    channels,
                    zRange,
                    tRange,
                    histogramBins != null ? histogramBins : DEFAULT_HISTOGRAM_BINS,
                    timeout != null ? timeout : DEFAULT_TIMEOUT,
                    maxBytes != null ? maxBytes : DEFAULT_MAX_BYTES);
        }

        /** Minimal: all defaults, all channels, adaptive Z and T. */
        public static Request of(String path) {
            return of(path, null, null, null, null, null, null, null);
        }

        /** Whether Z and/or T ranges were left to defaults (adaptive). */
        boolean isAdaptive() {
            return zRange == null || tRange == null;
        }
    }

    /**
     * The result of a stats computation: per-channel statistics plus
     * the ranges that were actually included, so the caller knows
     * exactly what "all" resolved to.
     *
     * @param perChannel   one {@link IntensityStats} per channel
     * @param channels     the channels that were computed (inclusive range)
     * @param zRange       the Z-slices included (inclusive range, before
     *                     any subsampling)
     * @param tRange       the timepoints included (inclusive range, before
     *                     any subsampling)
     * @param zSlicesUsed  actual Z-slice indices read (may be subsampled)
     * @param timepointsUsed actual timepoint indices read (may be subsampled)
     * @param pixelType    the pixel type of the series
     */
    public record StatsResult(
            List<IntensityStats> perChannel,
            Range channels,
            Range zRange,
            Range tRange,
            int[] zSlicesUsed,
            int[] timepointsUsed,
            PixelType pixelType) {

        public StatsResult {
            perChannel = List.copyOf(perChannel);
            zSlicesUsed = zSlicesUsed.clone();
            timepointsUsed = timepointsUsed.clone();
        }
    }

    /**
     * Execute the get_intensity_stats tool.
     *
     * @param request       the stats parameters
     * @param pathValidator  validates whether the path is accessible
     * @param readerFactory  creates a new (unopened) reader instance
     * @return a structured result containing per-channel stats or a failure
     */
    public static ToolResult<StatsResult> execute(
            Request request,
            PathValidator pathValidator,
            Supplier<ImageReader> readerFactory) {

        // 1. Validate path
        var access = pathValidator.check(request.path());
        if (access instanceof AccessResult.Denied denied) {
            return ToolResult.accessDenied(denied.reason());
        }
        var canonicalPath = ((AccessResult.Allowed) access).canonicalPath();

        // 2. Run with timeout
        var task = new CancellableTask(request.timeout());
        var result = task.run(() -> {
            try (var reader = readerFactory.get()) {
                reader.open(canonicalPath);

                var meta = reader.getMetadata(
                        request.series(), DetailLevel.SUMMARY);
                var si = meta.detailedSeries();

                // Resolve channel range (always fully resolved)
                Range channelRange = resolveChannelRange(
                        request.channels(), si);
                int[] channelIndices = channelRange.toArray();

                // Full Z and T ranges (what the series offers)
                Range fullZ = new Range(0, si.sizeZ() - 1);
                Range fullT = new Range(0, si.sizeT() - 1);

                // Resolve explicit ranges (validate bounds)
                Range requestedZ = request.zRange() != null
                        ? validateZRange(request.zRange(), si) : fullZ;
                Range requestedT = request.tRange() != null
                        ? validateTRange(request.tRange(), si) : fullT;

                long bytesPerPlane = (long) si.sizeX() * si.sizeY()
                        * si.pixelType().bytesPerPixel();

                // Sanity: must fit at least one plane per channel
                if (bytesPerPlane * channelIndices.length
                        > request.maxBytes()) {
                    throw new IllegalArgumentException(
                            "byte budget (" + request.maxBytes()
                            + ") too small for even one plane per channel"
                            + " (" + bytesPerPlane + " bytes/plane, "
                            + channelIndices.length + " channels)");
                }

                ByteOrder order = reader.isLittleEndian(request.series())
                        ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

                boolean adaptive = request.zRange() == null
                        || request.tRange() == null;

                if (adaptive) {
                    return readAdaptive(
                            reader, request, si, channelRange,
                            channelIndices, requestedZ, requestedT,
                            bytesPerPlane, order);
                } else {
                    return readExplicit(
                            reader, request, si, channelRange,
                            channelIndices, requestedZ, requestedT,
                            bytesPerPlane, order);
                }
            }
        });

        return unwrap(result);
    }

    // ================================================================
    // Explicit reading: all requested planes, subsampled if over budget
    // ================================================================

    private static StatsResult readExplicit(
            ImageReader reader, Request request, SeriesInfo si,
            Range channelRange, int[] channelIndices,
            Range zRange, Range tRange,
            long bytesPerPlane, ByteOrder order) throws IOException {

        int[] zIndices = zRange.toArray();
        int[] tIndices = tRange.toArray();

        long totalPlanes = (long) channelIndices.length
                * zIndices.length * tIndices.length;
        long totalBytes = bytesPerPlane * totalPlanes;

        boolean sampled = false;
        double sampledFraction = 1.0;
        int[] usedZ = zIndices;
        int[] usedT = tIndices;

        if (totalBytes > request.maxBytes()) {
            long maxPlanes = request.maxBytes() / bytesPerPlane;
            long planesPerChannel = maxPlanes / channelIndices.length;
            var sub = subsamplePlanes(
                    zIndices, tIndices, (int) planesPerChannel);
            usedZ = sub.zSlices;
            usedT = sub.timepoints;
            sampled = true;
            long sampledPlanes = (long) channelIndices.length
                    * usedZ.length * usedT.length;
            sampledFraction = (double) sampledPlanes / totalPlanes;
        }

        var accumulators = createAccumulators(channelIndices, si.pixelType());
        readPlanes(reader, request.series(), channelIndices,
                usedZ, usedT, accumulators, order);

        return buildResult(accumulators, request.histogramBins(),
                sampled, sampledFraction, channelRange, zRange, tRange,
                usedZ, usedT, si.pixelType());
    }

    // ================================================================
    // Adaptive reading: monitor rate, stop before exceeding budget
    // ================================================================

    private static StatsResult readAdaptive(
            ImageReader reader, Request request, SeriesInfo si,
            Range channelRange, int[] channelIndices,
            Range fullZ, Range fullT,
            long bytesPerPlane, ByteOrder order) throws IOException {

        int[] allZ = fullZ.toArray();
        int[] allT = fullT.toArray();
        int nChannels = channelIndices.length;

        // Decide step granularity:
        // If both Z > 1 and T > 1, step by full Z-volume per timepoint.
        // Otherwise, step by individual planes (one z,t combo × all channels).
        boolean volumeMode = allZ.length > 1 && allT.length > 1;
        int planesPerStep = volumeMode
                ? nChannels * allZ.length   // one full Z-stack × all channels
                : nChannels;                // one plane × all channels

        long bytesPerStep = bytesPerPlane * planesPerStep;
        long budgetNanos = request.timeout().toNanos();
        // Use 90% of the timeout as the read budget — leave room for
        // finalization (histogram, percentiles, etc.)
        long readBudgetNanos = (long) (budgetNanos * 0.9);

        var estimator = new ReadRateEstimator(readBudgetNanos, BUDGET_CONFIDENCE);
        var accumulators = createAccumulators(channelIndices, si.pixelType());

        long bytesRead = 0;
        long startNanos = System.nanoTime();

        // Track which Z and T indices we actually read
        var usedZList = new ArrayList<Integer>();
        var usedTList = new ArrayList<Integer>();

        if (volumeMode) {
            // Step through timepoints, reading full Z-stacks
            for (int ti = 0; ti < allT.length; ti++) {
                int t = allT[ti];

                // Byte budget check (exact)
                if (bytesRead + bytesPerStep > request.maxBytes()) {
                    break;
                }

                // Time budget check (estimated, after minimum observations)
                if (!estimator.canAfford(planesPerStep)) {
                    break;
                }

                // Read full Z-stack for this timepoint
                for (int z : allZ) {
                    for (int i = 0; i < nChannels; i++) {
                        byte[] raw = reader.readPlane(
                                request.series(), channelIndices[i], z, t);
                        accumulators[i].addPlane(raw, order);
                    }
                }
                bytesRead += bytesPerStep;
                usedTList.add(t);

                long elapsed = System.nanoTime() - startNanos;
                estimator.recordStep(planesPerStep, elapsed);

                // Record Z indices on first pass
                if (ti == 0) {
                    for (int z : allZ) usedZList.add(z);
                }
            }
        } else {
            // Step through (z, t) pairs individually
            // Iterate T outer, Z inner — consistent with volume mode
            for (int t : allT) {
                for (int z : allZ) {
                    // Byte budget check (exact)
                    if (bytesRead + bytesPerStep > request.maxBytes()) {
                        break;
                    }

                    // Time budget check (estimated)
                    if (!estimator.canAfford(planesPerStep)) {
                        break;
                    }

                    for (int i = 0; i < nChannels; i++) {
                        byte[] raw = reader.readPlane(
                                request.series(), channelIndices[i], z, t);
                        accumulators[i].addPlane(raw, order);
                    }
                    bytesRead += bytesPerStep;

                    long elapsed = System.nanoTime() - startNanos;
                    estimator.recordStep(planesPerStep, elapsed);

                    if (!usedZList.contains(z)) usedZList.add(z);
                    if (!usedTList.contains(t)) usedTList.add(t);
                }
                // If inner loop broke out early, stop outer too
                if (bytesRead + bytesPerStep > request.maxBytes()
                        || !estimator.canAfford(planesPerStep)) {
                    break;
                }
            }
        }

        // Determine what we actually read
        int[] usedZ = usedZList.stream().mapToInt(Integer::intValue).toArray();
        int[] usedT = usedTList.stream().mapToInt(Integer::intValue).toArray();

        if (usedZ.length == 0 || usedT.length == 0) {
            throw new IllegalStateException(
                    "budget too small to read even a single step");
        }

        // Compute the full requested ranges for reporting
        Range reportZ = fullZ;
        Range reportT = fullT;

        long totalPlanes = (long) nChannels * allZ.length * allT.length;
        long actualPlanes = estimator.totalPlanesRead();
        boolean sampled = actualPlanes < totalPlanes;
        double sampledFraction = (double) actualPlanes / totalPlanes;

        return buildResult(accumulators, request.histogramBins(),
                sampled, sampledFraction, channelRange, reportZ, reportT,
                usedZ, usedT, si.pixelType());
    }

    // ================================================================
    // Shared helpers
    // ================================================================

    private static StatsAccumulator[] createAccumulators(
            int[] channelIndices, PixelType pixelType) {
        var accumulators = new StatsAccumulator[channelIndices.length];
        for (int i = 0; i < channelIndices.length; i++) {
            accumulators[i] = StatsAccumulator.create(
                    channelIndices[i], pixelType);
        }
        return accumulators;
    }

    private static void readPlanes(
            ImageReader reader, int series, int[] channelIndices,
            int[] zIndices, int[] tIndices,
            StatsAccumulator[] accumulators, ByteOrder order)
            throws IOException {
        for (int t : tIndices) {
            for (int z : zIndices) {
                for (int i = 0; i < channelIndices.length; i++) {
                    byte[] raw = reader.readPlane(
                            series, channelIndices[i], z, t);
                    accumulators[i].addPlane(raw, order);
                }
            }
        }
    }

    private static StatsResult buildResult(
            StatsAccumulator[] accumulators, int histogramBins,
            boolean sampled, double sampledFraction,
            Range channelRange, Range zRange, Range tRange,
            int[] usedZ, int[] usedT, PixelType pixelType) {
        var perChannel = new ArrayList<IntensityStats>(accumulators.length);
        for (var acc : accumulators) {
            perChannel.add(acc.finish(histogramBins, sampled, sampledFraction));
        }
        return new StatsResult(
                perChannel, channelRange, zRange, tRange,
                usedZ, usedT, pixelType);
    }

    // ---- Range resolution ----

    static Range resolveChannelRange(Range requested, SeriesInfo si) {
        if (requested == null) {
            return new Range(0, si.sizeC() - 1);
        }
        if (requested.end() >= si.sizeC()) {
            throw new IllegalArgumentException(
                    "channel range end " + requested.end()
                    + " out of range, series has " + si.sizeC()
                    + " channel(s)");
        }
        return requested;
    }

    private static Range validateZRange(Range requested, SeriesInfo si) {
        if (requested.end() >= si.sizeZ()) {
            throw new IllegalArgumentException(
                    "z range end " + requested.end()
                    + " out of range, series has " + si.sizeZ()
                    + " Z-slice(s)");
        }
        return requested;
    }

    private static Range validateTRange(Range requested, SeriesInfo si) {
        if (requested.end() >= si.sizeT()) {
            throw new IllegalArgumentException(
                    "timepoint range end " + requested.end()
                    + " out of range, series has " + si.sizeT()
                    + " timepoint(s)");
        }
        return requested;
    }

    // ---- Subsampling ----

    /**
     * Reduce the number of Z-slices and timepoints to fit within a
     * plane budget.  Evenly spaces selections across the available
     * range, preferring to keep Z-slices and reduce timepoints first.
     */
    record SubsampledPlanes(int[] zSlices, int[] timepoints) {}

    static SubsampledPlanes subsamplePlanes(
            int[] zSlices, int[] timepoints, int maxPlanes) {
        if (zSlices.length <= maxPlanes) {
            int tCount = maxPlanes / zSlices.length;
            return new SubsampledPlanes(
                    zSlices, evenlySpaced(timepoints, tCount));
        }
        return new SubsampledPlanes(
                evenlySpaced(zSlices, maxPlanes),
                new int[] { timepoints[0] });
    }

    /**
     * Select up to {@code count} evenly spaced elements from the
     * source array.
     */
    static int[] evenlySpaced(int[] source, int count) {
        if (count >= source.length) return source;
        if (count <= 0) return new int[] { source[0] };
        if (count == 1) return new int[] { source[source.length / 2] };

        var result = new int[count];
        for (int i = 0; i < count; i++) {
            int idx = (int) Math.round(
                    (double) i * (source.length - 1) / (count - 1));
            result[i] = source[idx];
        }
        return result;
    }

    // ---- CancellableTask.Result → ToolResult conversion ----

    private static <T> ToolResult<T> unwrap(Result<T> result) {
        return switch (result) {
            case Result.Completed<T> c -> ToolResult.success(c.value());
            case Result.Failed<T> f -> convertError(f.error());
            case Result.TimedOut<T> t -> ToolResult.timeout(
                    "Operation timed out after " + t.elapsed().toMillis()
                    + " ms (interrupted " + t.interruptsSent() + " time(s),"
                    + " thread "
                    + (t.threadStillAlive() ? "still alive" : "terminated")
                    + ")");
        };
    }

    private static <T> ToolResult<T> convertError(Throwable error) {
        if (error instanceof InterruptedException) {
            return ToolResult.timeout(
                    "Operation was interrupted (likely timed out)");
        }
        if (error instanceof IllegalArgumentException) {
            return ToolResult.invalidArgument(error.getMessage());
        }
        if (error instanceof IOException) {
            return ToolResult.ioError(error.getMessage(), error);
        }
        return ToolResult.ioError(
                error.getClass().getSimpleName() + ": " + error.getMessage(),
                error);
    }
}
