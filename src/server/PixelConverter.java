package lab.kerrr.mcpbio.bioimageserver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Converts raw pixel byte arrays (as returned by {@link ImageReader#readPlane})
 * into interpretable numeric values and then into display-ready uint8.
 *
 * <p>This is the bridge between format-specific byte layouts and the
 * display/analysis code in tools.  All pixel type and byte order handling
 * is centralized here.
 */
public final class PixelConverter {

    private PixelConverter() {}

    /**
     * Extract pixel values from a raw byte array as doubles.
     *
     * <p>Each pixel is interpreted according to the given type and byte
     * order.  The returned array has one entry per pixel (length =
     * {@code raw.length / type.bytesPerPixel()}).
     *
     * @param raw   raw pixel bytes (row-major, from {@link ImageReader#readPlane})
     * @param type  the pixel data type
     * @param order byte order (from {@link ImageReader#isLittleEndian})
     * @return pixel values as doubles
     */
    public static double[] toDoubles(byte[] raw, PixelType type, ByteOrder order) {
        int bpp = type.bytesPerPixel();
        int count = raw.length / bpp;
        var doubles = new double[count];
        var buf = ByteBuffer.wrap(raw).order(order);

        for (int i = 0; i < count; i++) {
            doubles[i] = switch (type) {
                case BIT, UINT8  -> Byte.toUnsignedInt(buf.get());
                case INT8        -> buf.get();
                case UINT16      -> Short.toUnsignedInt(buf.getShort());
                case INT16       -> buf.getShort();
                case UINT32      -> Integer.toUnsignedLong(buf.getInt());
                case INT32       -> buf.getInt();
                case FLOAT       -> buf.getFloat();
                case DOUBLE      -> buf.getDouble();
            };
        }
        return doubles;
    }

    /**
     * Map pixel values to uint8 (0–255) by linearly scaling the range
     * [{@code low}, {@code high}] to [0, 255].  Values outside the range
     * are clamped.
     *
     * @param values pixel values (e.g. from {@link #toDoubles})
     * @param low    the value that maps to 0
     * @param high   the value that maps to 255
     * @return uint8 pixel values (unsigned; use {@code Byte.toUnsignedInt}
     *         to read)
     */
    public static byte[] toUint8(double[] values, double low, double high) {
        var result = new byte[values.length];
        if (high <= low) {
            // Degenerate range — all pixels become 0
            return result;
        }
        double scale = 255.0 / (high - low);
        for (int i = 0; i < values.length; i++) {
            double mapped = (values[i] - low) * scale;
            int clamped = Math.clamp((int) Math.round(mapped), 0, 255);
            result[i] = (byte) clamped;
        }
        return result;
    }

    /**
     * Map pixel values to uint8 using the full range of the pixel type.
     * Type minimum maps to 0, type maximum maps to 255.
     *
     * <p>For floating-point types (where min/max are infinite), this
     * falls back to the actual data range (equivalent to min/max
     * auto-contrast).
     *
     * @param values pixel values
     * @param type   the pixel type (determines the mapping range)
     * @return uint8 pixel values
     */
    public static byte[] toUint8FullRange(double[] values, PixelType type) {
        double low = type.minValue();
        double high = type.maxValue();
        if (Double.isInfinite(low) || Double.isInfinite(high)) {
            // Floating-point: use actual data range
            low = Double.MAX_VALUE;
            high = -Double.MAX_VALUE;
            for (double v : values) {
                if (v < low) low = v;
                if (v > high) high = v;
            }
        }
        return toUint8(values, low, high);
    }

    /**
     * Map pixel values to uint8 using percentile-based auto-contrast.
     *
     * <p>The values at the given percentiles become 0 and 255; everything
     * outside is clamped.  This handles hot pixels and background noise
     * gracefully.
     *
     * @param values        pixel values
     * @param lowPercentile  percentile for the black point (e.g. 0.1)
     * @param highPercentile percentile for the white point (e.g. 99.9)
     * @return uint8 pixel values
     */
    public static byte[] toUint8AutoContrast(double[] values,
                                              double lowPercentile,
                                              double highPercentile) {
        double low = percentile(values, lowPercentile);
        double high = percentile(values, highPercentile);
        return toUint8(values, low, high);
    }

    /**
     * Compute a percentile of the given values.
     *
     * <p>Uses linear interpolation between nearest ranks.
     *
     * @param values the data (not modified)
     * @param p      percentile in [0, 100]
     * @return the percentile value
     */
    public static double percentile(double[] values, double p) {
        if (values.length == 0) {
            throw new IllegalArgumentException("cannot compute percentile of empty array");
        }
        if (p < 0 || p > 100) {
            throw new IllegalArgumentException(
                    "percentile must be in [0, 100], got: " + p);
        }
        var sorted = values.clone();
        Arrays.sort(sorted);
        if (p == 0) return sorted[0];
        if (p == 100) return sorted[sorted.length - 1];

        double rank = (p / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = Math.min(lo + 1, sorted.length - 1);
        double frac = rank - lo;
        return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
    }
}
