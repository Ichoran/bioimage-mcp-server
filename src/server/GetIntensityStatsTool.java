package lab.kerrr.mcpbio.bioimageserver;

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
     * @param channels       channel selection (slice)
     * @param z              Z-slice selection (slice); ":" reads adaptively
     * @param t              timepoint selection (slice); ":" reads adaptively
     * @param histogramBins  number of bins for the output histogram
     * @param timeout        wall-clock time limit
     * @param maxBytes       approximate cap on raw pixel bytes to read
     */
    public record Request(
            String path,
            int series,
            Slice channels,
            Slice z,
            Slice t,
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
            if (channels == null || z == null || t == null) {
                throw new IllegalArgumentException(
                        "channels, z, and t selections are required "
                        + "(use Slice.all() for ':')");
            }
        }

        /**
         * Full-featured factory.  The {@code channels}/{@code z}/{@code t}
         * selections are required; pass {@link Slice#all()} for "all".  A
         * {@code z} or {@code t} of {@code ":"} (i.e. {@link Slice#isAll()})
         * reads adaptively within the budget; a bounded slice reads exactly.
         */
        public static Request of(String path, Integer series,
                                  Slice channels, Slice z, Slice t,
                                  Integer histogramBins,
                                  Duration timeout, Long maxBytes) {
            return new Request(
                    path,
                    series != null ? series : 0,
                    channels,
                    z,
                    t,
                    histogramBins != null ? histogramBins : DEFAULT_HISTOGRAM_BINS,
                    timeout != null ? timeout : DEFAULT_TIMEOUT,
                    maxBytes != null ? maxBytes : DEFAULT_MAX_BYTES);
        }

        /** All channels, fully adaptive Z and T (every selector ":"). */
        public static Request of(String path) {
            return of(path, null, Slice.all(), Slice.all(), Slice.all(),
                    null, null, null);
        }

        /**
         * Whether Z and/or T are ":" and therefore read adaptively (stopping
         * before the time/byte budget is exceeded).  A bounded slice on a
         * dimension reads exactly that range (subsampling only if over the
         * byte budget).
         */
        boolean isAdaptive() {
            return z.isAll() || t.isAll();
        }
    }

    /**
     * The result of a stats computation: per-channel statistics plus
     * the ranges that were actually included, so the caller knows
     * exactly what "all" resolved to.
     *
     * @param perChannel   one {@link IntensityStats} per channel
     * @param channels     the channels that were computed
     * @param zRequested   the Z-slices requested (before any subsampling)
     * @param tRequested   the timepoints requested (before any subsampling)
     * @param zSlicesUsed  actual Z-slice indices read (may be subsampled)
     * @param timepointsUsed actual timepoint indices read (may be subsampled)
     * @param pixelType    the pixel type of the series
     */
    public record StatsResult(
            List<IntensityStats> perChannel,
            int[] channels,
            int[] zRequested,
            int[] tRequested,
            int[] zSlicesUsed,
            int[] timepointsUsed,
            PixelType pixelType) {

        public StatsResult {
            perChannel = List.copyOf(perChannel);
            channels = channels.clone();
            zRequested = zRequested.clone();
            tRequested = tRequested.clone();
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

                // Resolve the selections to concrete indices.  Channels and
                // bounded Z/T may be any list (e.g. "0,2", "4:9,11:").
                int[] channelIndices =
                        request.channels().resolve(si.sizeC(), "channels");

                // ":" → adaptive over the whole dimension; a bounded slice is
                // resolved and read exactly.
                int[] requestedZ = request.z().isAll()
                        ? intRange(si.sizeZ()) : request.z().resolve(si.sizeZ(), "z");
                int[] requestedT = request.t().isAll()
                        ? intRange(si.sizeT()) : request.t().resolve(si.sizeT(), "t");

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

                if (request.isAdaptive()) {
                    return readAdaptive(
                            reader, request, si, channelIndices,
                            requestedZ, requestedT, bytesPerPlane, order);
                } else {
                    return readExplicit(
                            reader, request, si, channelIndices,
                            requestedZ, requestedT, bytesPerPlane, order);
                }
            }
        });

        return ToolResult.unwrap(result);
    }

    // ================================================================
    // Explicit reading: all requested planes, subsampled if over budget
    // ================================================================

    private static StatsResult readExplicit(
            ImageReader reader, Request request, SeriesInfo si,
            int[] channelIndices, int[] zIndices, int[] tIndices,
            long bytesPerPlane, ByteOrder order) throws IOException {

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
                sampled, sampledFraction, channelIndices, zIndices, tIndices,
                usedZ, usedT, si.pixelType());
    }

    // ================================================================
    // Adaptive reading: monitor rate, stop before exceeding budget
    // ================================================================

    private static StatsResult readAdaptive(
            ImageReader reader, Request request, SeriesInfo si,
            int[] channelIndices, int[] allZ, int[] allT,
            long bytesPerPlane, ByteOrder order) throws IOException {

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

        long totalPlanes = (long) nChannels * allZ.length * allT.length;
        long actualPlanes = estimator.totalPlanesRead();
        boolean sampled = actualPlanes < totalPlanes;
        double sampledFraction = (double) actualPlanes / totalPlanes;

        // Report the full requested extent (allZ/allT) vs what was used.
        return buildResult(accumulators, request.histogramBins(),
                sampled, sampledFraction, channelIndices, allZ, allT,
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
            int[] channels, int[] zRequested, int[] tRequested,
            int[] usedZ, int[] usedT, PixelType pixelType) {
        var perChannel = new ArrayList<IntensityStats>(accumulators.length);
        for (var acc : accumulators) {
            perChannel.add(acc.finish(histogramBins, sampled, sampledFraction));
        }
        return new StatsResult(
                perChannel, channels, zRequested, tRequested,
                usedZ, usedT, pixelType);
    }

    /** The indices {@code [0, n)} as an array. */
    private static int[] intRange(int n) {
        var arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;
        return arr;
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

}
