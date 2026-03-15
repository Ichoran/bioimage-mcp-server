package lab.kerrr.mcpbio.bioimageserver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Accumulates a pixel-wise maximum intensity projection across
 * multiple planes, then renders the result to display-ready uint8.
 *
 * <p>Type-specialized: for 8- and 16-bit integer types, accumulation
 * uses {@code int[]} arrays and percentile calculation uses a
 * counting-sort histogram — no double[] intermediates, no O(n log n)
 * sort.  For 32-bit integer and floating-point types, accumulation
 * uses {@code double[]} with a conventional sort-based percentile.
 *
 * <p>Instances are created via {@link #create(PixelType, int)}.
 * The typical lifecycle is:
 * <pre>
 *     var proj = MaxProjection.create(type, nPixels);
 *     proj.addPlane(raw1, order);
 *     var backup = proj.fork();       // snapshot
 *     proj.addPlane(raw2, order);     // accumulate more
 *     byte[] result = proj.toUint8AutoContrast(0.1, 99.9);
 * </pre>
 */
sealed interface MaxProjection {

    /**
     * Accumulate a plane: for each pixel, take the max of the
     * existing value and the new value from the raw byte array.
     */
    void addPlane(byte[] raw, ByteOrder order);

    /**
     * Create an independent copy of the current projection state.
     * Modifications to the copy do not affect the original.
     */
    MaxProjection fork();

    /**
     * Render the current max-projection values to uint8 using
     * percentile-based auto-contrast.
     *
     * <p>The percentile calculation uses the same interpolation
     * formula as {@link PixelConverter#percentile}: linear
     * interpolation between nearest ranks.
     *
     * @param lowPercentile  black point percentile (e.g. 0.1)
     * @param highPercentile white point percentile (e.g. 99.9)
     * @return uint8 pixel values
     */
    byte[] toUint8AutoContrast(double lowPercentile, double highPercentile);

    /** Create a projector appropriate for the given pixel type. */
    static MaxProjection create(PixelType type, int nPixels) {
        return switch (type) {
            case BIT, UINT8, INT8, UINT16, INT16
                -> new IntMaxProjection(type, nPixels);
            case INT32, UINT32, FLOAT, DOUBLE
                -> new DoubleMaxProjection(type, nPixels);
        };
    }

    // ================================================================
    // int[] implementation for ≤16-bit types
    // ================================================================

    /**
     * Max projection for BIT, UINT8, INT8, UINT16, INT16.
     *
     * <p>Working state is {@code int[nPixels]}, initialized to
     * {@link Integer#MIN_VALUE} so the first plane's values always
     * win.  Percentiles are computed via a counting histogram (O(n))
     * rather than sorting (O(n log n)).
     */
    final class IntMaxProjection implements MaxProjection {

        private final PixelType type;
        private final int nPixels;
        private final int[] values;

        /** Histogram range (256 for 8-bit types, 65536 for 16-bit). */
        private final int histogramRange;

        /**
         * Offset to map signed values to non-negative histogram
         * indices: 0 for unsigned types, 128 for INT8, 32768 for
         * INT16.
         */
        private final int histogramOffset;

        IntMaxProjection(PixelType type, int nPixels) {
            this.type = type;
            this.nPixels = nPixels;
            this.values = new int[nPixels];
            Arrays.fill(values, Integer.MIN_VALUE);

            this.histogramRange = switch (type) {
                case BIT             -> 2;
                case UINT8, INT8     -> 256;
                case UINT16, INT16   -> 65536;
                default -> throw new IllegalArgumentException(
                        "IntMaxProjection does not support " + type);
            };
            this.histogramOffset = switch (type) {
                case INT8  -> -Byte.MIN_VALUE;   // 128
                case INT16 -> -Short.MIN_VALUE;  // 32768
                default    -> 0;
            };
        }

        private IntMaxProjection(IntMaxProjection src) {
            this.type = src.type;
            this.nPixels = src.nPixels;
            this.values = src.values.clone();
            this.histogramRange = src.histogramRange;
            this.histogramOffset = src.histogramOffset;
        }

        @Override
        public void addPlane(byte[] raw, ByteOrder order) {
            switch (type) {
                case BIT, UINT8 -> addPlaneUint8(raw);
                case INT8       -> addPlaneInt8(raw);
                case UINT16     -> addPlaneUint16(raw, order);
                case INT16      -> addPlaneInt16(raw, order);
                default -> throw new AssertionError();
            }
        }

        private void addPlaneUint8(byte[] raw) {
            for (int p = 0; p < nPixels; p++) {
                int v = Byte.toUnsignedInt(raw[p]);
                if (v > values[p]) values[p] = v;
            }
        }

        private void addPlaneInt8(byte[] raw) {
            for (int p = 0; p < nPixels; p++) {
                int v = raw[p];  // sign-extended
                if (v > values[p]) values[p] = v;
            }
        }

        private void addPlaneUint16(byte[] raw, ByteOrder order) {
            var buf = ByteBuffer.wrap(raw).order(order);
            for (int p = 0; p < nPixels; p++) {
                int v = Short.toUnsignedInt(buf.getShort());
                if (v > values[p]) values[p] = v;
            }
        }

        private void addPlaneInt16(byte[] raw, ByteOrder order) {
            var buf = ByteBuffer.wrap(raw).order(order);
            for (int p = 0; p < nPixels; p++) {
                int v = buf.getShort();  // sign-extended
                if (v > values[p]) values[p] = v;
            }
        }

        @Override
        public MaxProjection fork() {
            return new IntMaxProjection(this);
        }

        @Override
        public byte[] toUint8AutoContrast(double lowPercentile,
                                           double highPercentile) {
            // Build histogram
            var histogram = new long[histogramRange];
            for (int i = 0; i < nPixels; i++) {
                histogram[values[i] + histogramOffset]++;
            }

            // Find percentile values using the same interpolation
            // as PixelConverter.percentile
            double low = percentileFromHistogram(
                    histogram, nPixels, lowPercentile);
            double high = percentileFromHistogram(
                    histogram, nPixels, highPercentile);

            // Map to uint8
            var result = new byte[nPixels];
            if (high <= low) return result;
            double scale = 255.0 / (high - low);
            for (int i = 0; i < nPixels; i++) {
                double mapped = (values[i] - low) * scale;
                result[i] = (byte) Math.clamp(
                        (int) Math.round(mapped), 0, 255);
            }
            return result;
        }

        /**
         * Compute a percentile from a cumulative histogram using the
         * same linear-interpolation formula as
         * {@link PixelConverter#percentile}.
         *
         * @param histogram  bin counts (index = value + histogramOffset)
         * @param totalPixels total number of pixels
         * @param p           percentile in [0, 100]
         * @return the interpolated percentile value (in original scale,
         *         not histogram-offset scale)
         */
        private double percentileFromHistogram(
                long[] histogram, int totalPixels, double p) {
            double rank = (p / 100.0) * (totalPixels - 1);
            int loIdx = (int) Math.floor(rank);
            int hiIdx = Math.min(loIdx + 1, totalPixels - 1);
            double frac = rank - loIdx;

            int loVal = valueAtSortedPosition(histogram, loIdx);
            int hiVal = valueAtSortedPosition(histogram, hiIdx);
            return loVal + frac * (hiVal - loVal);
        }

        /**
         * Find the value at a given position in the sorted sequence
         * by walking the cumulative histogram.
         *
         * @param histogram bin counts
         * @param position  zero-based position in sorted order
         * @return the original value (histogram bin - offset)
         */
        private int valueAtSortedPosition(long[] histogram, int position) {
            long cumulative = 0;
            for (int bin = 0; bin < histogram.length; bin++) {
                cumulative += histogram[bin];
                if (cumulative > position) {
                    return bin - histogramOffset;
                }
            }
            // Should not reach here if position < totalPixels
            return histogram.length - 1 - histogramOffset;
        }
    }

    // ================================================================
    // double[] implementation for 32-bit and floating-point types
    // ================================================================

    /**
     * Max projection for INT32, UINT32, FLOAT, DOUBLE.
     *
     * <p>Working state is {@code double[nPixels]}, initialized to
     * {@link Double#NEGATIVE_INFINITY}.  Percentiles use the same
     * sort-based method as {@link PixelConverter#percentile}.
     */
    final class DoubleMaxProjection implements MaxProjection {

        private final PixelType type;
        private final int nPixels;
        private final double[] values;

        DoubleMaxProjection(PixelType type, int nPixels) {
            this.type = type;
            this.nPixels = nPixels;
            this.values = new double[nPixels];
            Arrays.fill(values, Double.NEGATIVE_INFINITY);
        }

        private DoubleMaxProjection(DoubleMaxProjection src) {
            this.type = src.type;
            this.nPixels = src.nPixels;
            this.values = src.values.clone();
        }

        @Override
        public void addPlane(byte[] raw, ByteOrder order) {
            var buf = ByteBuffer.wrap(raw).order(order);
            for (int p = 0; p < nPixels; p++) {
                double v = switch (type) {
                    case INT32  -> buf.getInt();
                    case UINT32 -> Integer.toUnsignedLong(buf.getInt());
                    case FLOAT  -> buf.getFloat();
                    case DOUBLE -> buf.getDouble();
                    default -> throw new AssertionError();
                };
                if (v > values[p]) values[p] = v;
            }
        }

        @Override
        public MaxProjection fork() {
            return new DoubleMaxProjection(this);
        }

        @Override
        public byte[] toUint8AutoContrast(double lowPercentile,
                                           double highPercentile) {
            double low = PixelConverter.percentile(values, lowPercentile);
            double high = PixelConverter.percentile(values, highPercentile);
            return PixelConverter.toUint8(values, low, high);
        }
    }
}
