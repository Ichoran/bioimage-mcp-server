package lab.kerrr.mcpbio.bioimageserver;

import com.tdunning.math.stats.MergingDigest;
import com.tdunning.math.stats.TDigest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Accumulates intensity statistics across one or more planes of pixel
 * data for a single channel, producing an {@link IntensityStats} at
 * the end.
 *
 * <p>Designed to handle large datasets efficiently:
 * <ul>
 *   <li>For narrow integer types (8-bit, 16-bit), uses exact counting
 *       arrays — every possible value gets its own counter, giving
 *       exact percentiles and histograms with zero approximation.
 *   <li>For wide types (32-bit integers, float, double), tracks
 *       min/max/count/sum/sumOfSquares online and uses a
 *       <a href="https://github.com/tdunning/t-digest">t-digest</a>
 *       for percentile estimation.  The histogram is derived from the
 *       t-digest at finish time, using the observed data range.
 * </ul>
 *
 * <h3>Performance</h3>
 * <p>The inner loops are specialized per pixel type — no switch or
 * virtual dispatch per pixel.  The type switch happens once per
 * {@link #addPlane} call, then a dedicated private method runs a
 * tight loop for that type.  This is the same pattern ImageJ uses
 * for type-generic pixel processing.
 *
 * <p>Usage:
 * <pre>
 *     var acc = StatsAccumulator.create(channel, pixelType);
 *     acc.addPlane(rawBytes, byteOrder);  // one or more planes
 *     acc.addPlane(rawBytes2, byteOrder);
 *     IntensityStats stats = acc.finish(histogramBins, sampled, sampledFraction);
 * </pre>
 *
 * <p>Not thread-safe.  Use one accumulator per channel.
 */
public sealed abstract class StatsAccumulator {

    final int channel;
    final PixelType pixelType;
    long count;
    double welfordMean;
    double welfordM2;
    double min;
    double max;
    long saturationLow;
    long saturationHigh;

    StatsAccumulator(int channel, PixelType pixelType) {
        this.channel = channel;
        this.pixelType = pixelType;
        this.min = Double.POSITIVE_INFINITY;
        this.max = Double.NEGATIVE_INFINITY;
    }

    /**
     * Create an accumulator appropriate for the given pixel type.
     *
     * @param channel   channel index (carried through to the result)
     * @param pixelType determines which accumulator strategy to use
     */
    public static StatsAccumulator create(int channel, PixelType pixelType) {
        return switch (pixelType) {
            case BIT, UINT8, INT8, UINT16, INT16
                -> new ExactAccumulator(channel, pixelType);
            case UINT32, INT32, FLOAT, DOUBLE
                -> new DigestAccumulator(channel, pixelType);
        };
    }

    /**
     * Add a plane of raw pixel data to the accumulator.
     *
     * @param raw   raw bytes as returned by {@link ImageReader#readPlane}
     * @param order byte order for interpreting multi-byte types
     */
    public abstract void addPlane(byte[] raw, ByteOrder order);

    /**
     * Finalize the accumulation and produce statistics.
     *
     * @param histogramBins   number of bins for the output histogram
     * @param sampled         whether the data was subsampled
     * @param sampledFraction fraction of total pixels examined (1.0 if not sampled)
     * @return the computed intensity statistics
     * @throws IllegalStateException if no data was added
     */
    public abstract IntensityStats finish(int histogramBins,
                                          boolean sampled,
                                          double sampledFraction);

    /** Number of pixels accumulated so far. */
    public long count() { return count; }

    // ---- Shared statistics helpers ----

    double computeStddev() {
        if (count < 2) return 0.0;
        return Math.sqrt(welfordM2 / count);
    }

    double computeBitDepthUtilization() {
        double typeMin = pixelType.minValue();
        double typeMax = pixelType.maxValue();
        if (Double.isInfinite(typeMin) || Double.isInfinite(typeMax)) {
            return Double.NaN;
        }
        double typeRange = typeMax - typeMin;
        if (typeRange == 0) return 1.0;  // BIT type
        return (max - min) / typeRange;
    }

    IntensityStats buildStats(double median, Histogram histogram,
                              boolean sampled, double sampledFraction) {
        return new IntensityStats(
                channel, min, max, welfordMean, computeStddev(), median,
                histogram.binEdges, histogram.binCounts,
                (double) saturationLow / count,
                (double) saturationHigh / count,
                computeBitDepthUtilization(),
                sampled, sampledFraction);
    }

    // ================================================================
    // ExactAccumulator — for types where we can count every value
    // ================================================================

    static final class ExactAccumulator extends StatsAccumulator {

        private final long[] counts;
        private final int offset;
        // Cached type bounds for saturation checks
        private final int satLow;
        private final int satHigh;

        ExactAccumulator(int channel, PixelType pixelType) {
            super(channel, pixelType);
            int range = switch (pixelType) {
                case BIT           -> 2;
                case UINT8, INT8   -> 256;
                case UINT16, INT16 -> 65536;
                default -> throw new IllegalArgumentException(
                        "ExactAccumulator does not support " + pixelType);
            };
            this.counts = new long[range];
            this.offset = switch (pixelType) {
                case INT8  -> Byte.MIN_VALUE;
                case INT16 -> Short.MIN_VALUE;
                default    -> 0;
            };
            this.satLow = (int) pixelType.minValue();
            this.satHigh = (int) pixelType.maxValue();
        }

        @Override
        public void addPlane(byte[] raw, ByteOrder order) {
            switch (pixelType) {
                case BIT, UINT8 -> addPlaneUint8(raw);
                case INT8       -> addPlaneInt8(raw);
                case UINT16     -> addPlaneUint16(raw, order);
                case INT16      -> addPlaneInt16(raw, order);
                default -> throw new AssertionError();
            }
        }

        private void addPlaneUint8(byte[] raw) {
            for (int i = 0; i < raw.length; i++) {
                int v = Byte.toUnsignedInt(raw[i]);
                counts[v]++;
                // Inline Welford + min/max + saturation
                count++;
                double delta = v - welfordMean;
                welfordMean += delta / count;
                welfordM2 += delta * (v - welfordMean);
                if (v < min) min = v;
                if (v > max) max = v;
                if (v == satLow) saturationLow++;
                if (v == satHigh) saturationHigh++;
            }
        }

        private void addPlaneInt8(byte[] raw) {
            for (int i = 0; i < raw.length; i++) {
                int v = raw[i];  // sign-extended
                counts[v - offset]++;
                count++;
                double delta = v - welfordMean;
                welfordMean += delta / count;
                welfordM2 += delta * (v - welfordMean);
                if (v < min) min = v;
                if (v > max) max = v;
                if (v == satLow) saturationLow++;
                if (v == satHigh) saturationHigh++;
            }
        }

        private void addPlaneUint16(byte[] raw, ByteOrder order) {
            var buf = ByteBuffer.wrap(raw).order(order);
            int pixelCount = raw.length / 2;
            for (int i = 0; i < pixelCount; i++) {
                int v = Short.toUnsignedInt(buf.getShort());
                counts[v]++;
                count++;
                double delta = v - welfordMean;
                welfordMean += delta / count;
                welfordM2 += delta * (v - welfordMean);
                if (v < min) min = v;
                if (v > max) max = v;
                if (v == satLow) saturationLow++;
                if (v == satHigh) saturationHigh++;
            }
        }

        private void addPlaneInt16(byte[] raw, ByteOrder order) {
            var buf = ByteBuffer.wrap(raw).order(order);
            int pixelCount = raw.length / 2;
            for (int i = 0; i < pixelCount; i++) {
                int v = buf.getShort();
                counts[v - offset]++;
                count++;
                double delta = v - welfordMean;
                welfordMean += delta / count;
                welfordM2 += delta * (v - welfordMean);
                if (v < min) min = v;
                if (v > max) max = v;
                if (v == satLow) saturationLow++;
                if (v == satHigh) saturationHigh++;
            }
        }

        @Override
        public IntensityStats finish(int histogramBins,
                                     boolean sampled,
                                     double sampledFraction) {
            if (count == 0) {
                throw new IllegalStateException("no data added");
            }
            double median = exactPercentile(50.0);
            var histogram = buildHistogram(histogramBins);
            return buildStats(median, histogram, sampled, sampledFraction);
        }

        /**
         * Compute an exact percentile from the counting array.
         *
         * @param p percentile in [0, 100]
         */
        double exactPercentile(double p) {
            double rank = (p / 100.0) * (count - 1);
            long loRank = (long) Math.floor(rank);
            long hiRank = Math.min(loRank + 1, count - 1);
            double frac = rank - loRank;

            double loVal = valueAtRank(loRank);
            double hiVal = valueAtRank(hiRank);
            return loVal + frac * (hiVal - loVal);
        }

        private double valueAtRank(long rank) {
            long cumulative = 0;
            for (int i = 0; i < counts.length; i++) {
                cumulative += counts[i];
                if (cumulative > rank) {
                    return i + offset;
                }
            }
            return counts.length - 1 + offset;
        }

        private Histogram buildHistogram(int bins) {
            if (min == max) {
                return new Histogram(
                        new double[] { min, min }, new long[] { count });
            }

            var edges = new double[bins + 1];
            var hCounts = new long[bins];
            double binWidth = (max - min) / bins;

            for (int i = 0; i <= bins; i++) {
                edges[i] = min + i * binWidth;
            }
            edges[bins] = max;

            for (int i = 0; i < counts.length; i++) {
                if (counts[i] == 0) continue;
                double value = i + offset;
                int bin = (int) ((value - min) / binWidth);
                if (bin >= bins) bin = bins - 1;
                if (bin < 0) bin = 0;
                hCounts[bin] += counts[i];
            }

            return new Histogram(edges, hCounts);
        }
    }

    // ================================================================
    // DigestAccumulator — t-digest for wide/floating-point types
    // ================================================================

    static final class DigestAccumulator extends StatsAccumulator {

        /**
         * Compression parameter for the t-digest.  Higher values use
         * more memory but give better accuracy.  100 is the standard
         * default; it gives sub-1% relative error at extreme
         * percentiles (99.9th) with ~3–10 KB of memory.
         */
        static final double COMPRESSION = 100;

        private final TDigest digest;
        // Cached type bounds for saturation (finite integer types only)
        private final double satLow;
        private final double satHigh;
        private final boolean hasSaturationBounds;

        DigestAccumulator(int channel, PixelType pixelType) {
            super(channel, pixelType);
            this.digest = new MergingDigest(COMPRESSION);
            double lo = pixelType.minValue();
            double hi = pixelType.maxValue();
            this.hasSaturationBounds = !Double.isInfinite(lo)
                                    && !Double.isInfinite(hi);
            this.satLow = lo;
            this.satHigh = hi;
        }

        @Override
        public void addPlane(byte[] raw, ByteOrder order) {
            switch (pixelType) {
                case INT32  -> addPlaneInt32(raw, order);
                case UINT32 -> addPlaneUint32(raw, order);
                case FLOAT  -> addPlaneFloat(raw, order);
                case DOUBLE -> addPlaneDouble(raw, order);
                default -> throw new AssertionError();
            }
        }

        private void addPlaneInt32(byte[] raw, ByteOrder order) {
            var buf = ByteBuffer.wrap(raw).order(order);
            int pixelCount = raw.length / 4;
            for (int i = 0; i < pixelCount; i++) {
                double v = buf.getInt();
                count++;
                double delta = v - welfordMean;
                welfordMean += delta / count;
                welfordM2 += delta * (v - welfordMean);
                if (v < min) min = v;
                if (v > max) max = v;
                if (v == satLow) saturationLow++;
                if (v == satHigh) saturationHigh++;
                digest.add(v);
            }
        }

        private void addPlaneUint32(byte[] raw, ByteOrder order) {
            var buf = ByteBuffer.wrap(raw).order(order);
            int pixelCount = raw.length / 4;
            for (int i = 0; i < pixelCount; i++) {
                double v = Integer.toUnsignedLong(buf.getInt());
                count++;
                double delta = v - welfordMean;
                welfordMean += delta / count;
                welfordM2 += delta * (v - welfordMean);
                if (v < min) min = v;
                if (v > max) max = v;
                if (v == satLow) saturationLow++;
                if (v == satHigh) saturationHigh++;
                digest.add(v);
            }
        }

        private void addPlaneFloat(byte[] raw, ByteOrder order) {
            var buf = ByteBuffer.wrap(raw).order(order);
            int pixelCount = raw.length / 4;
            for (int i = 0; i < pixelCount; i++) {
                double v = buf.getFloat();
                count++;
                double delta = v - welfordMean;
                welfordMean += delta / count;
                welfordM2 += delta * (v - welfordMean);
                if (v < min) min = v;
                if (v > max) max = v;
                // No saturation for float (infinite bounds)
                digest.add(v);
            }
        }

        private void addPlaneDouble(byte[] raw, ByteOrder order) {
            var buf = ByteBuffer.wrap(raw).order(order);
            int pixelCount = raw.length / 8;
            for (int i = 0; i < pixelCount; i++) {
                double v = buf.getDouble();
                count++;
                double delta = v - welfordMean;
                welfordMean += delta / count;
                welfordM2 += delta * (v - welfordMean);
                if (v < min) min = v;
                if (v > max) max = v;
                // No saturation for double (infinite bounds)
                digest.add(v);
            }
        }

        @Override
        public IntensityStats finish(int histogramBins,
                                     boolean sampled,
                                     double sampledFraction) {
            if (count == 0) {
                throw new IllegalStateException("no data added");
            }

            double median = digest.quantile(0.5);
            var histogram = buildHistogram(histogramBins);
            return buildStats(median, histogram, sampled, sampledFraction);
        }

        private Histogram buildHistogram(int bins) {
            if (min == max) {
                return new Histogram(
                        new double[] { min, min }, new long[] { count });
            }

            var edges = new double[bins + 1];
            var hCounts = new long[bins];
            double binWidth = (max - min) / bins;

            for (int i = 0; i <= bins; i++) {
                edges[i] = min + i * binWidth;
            }
            edges[bins] = max;

            // Use the t-digest's CDF to estimate counts per bin.
            for (int i = 0; i < bins; i++) {
                double cdfLo = digest.cdf(edges[i]);
                double cdfHi = digest.cdf(edges[i + 1]);
                hCounts[i] = Math.round((cdfHi - cdfLo) * count);
            }

            // Correct rounding drift so histogram sums to count.
            long total = 0;
            for (long c : hCounts) total += c;
            if (total != count) {
                int maxBin = 0;
                for (int i = 1; i < bins; i++) {
                    if (hCounts[i] > hCounts[maxBin]) maxBin = i;
                }
                hCounts[maxBin] += count - total;
            }

            return new Histogram(edges, hCounts);
        }

        /** The underlying t-digest.  Visible for testing. */
        TDigest digest() { return digest; }
    }

    // ---- Shared helpers ----

    record Histogram(double[] binEdges, long[] binCounts) {}
}
