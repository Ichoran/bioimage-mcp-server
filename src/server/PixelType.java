package lab.kerrr.mcpbio.bioimageserver;

/**
 * Pixel data types supported by Bio-Formats.
 *
 * <p>The {@link #bytesPerPixel} field gives the size of a single sample.
 * {@link #signed} and {@link #floatingPoint} describe the numeric
 * interpretation.  These three fields, together with the label, are
 * enough for any downstream code to handle pixel buffers correctly
 * without depending on Bio-Formats constants.
 */
public enum PixelType {
    INT8   (1, true,  false),
    UINT8  (1, false, false),
    INT16  (2, true,  false),
    UINT16 (2, false, false),
    INT32  (4, true,  false),
    UINT32 (4, false, false),
    FLOAT  (4, true,  true),
    DOUBLE (8, true,  true),
    BIT    (1, false, false);  // packed bits; bytesPerPixel is nominal

    private final int bytesPerPixel;
    private final boolean signed;
    private final boolean floatingPoint;

    PixelType(int bytesPerPixel, boolean signed, boolean floatingPoint) {
        this.bytesPerPixel = bytesPerPixel;
        this.signed = signed;
        this.floatingPoint = floatingPoint;
    }

    public int bytesPerPixel() { return bytesPerPixel; }
    public boolean isSigned() { return signed; }
    public boolean isFloatingPoint() { return floatingPoint; }

    /** Bits per pixel (1 for BIT, 8 * bytesPerPixel otherwise). */
    public int bitsPerPixel() {
        return this == BIT ? 1 : bytesPerPixel * 8;
    }

    /**
     * The maximum representable value for integer types.
     * Returns {@link Double#POSITIVE_INFINITY} for floating-point types.
     */
    public double maxValue() {
        return switch (this) {
            case BIT    -> 1;
            case INT8   -> Byte.MAX_VALUE;
            case UINT8  -> 0xFF;
            case INT16  -> Short.MAX_VALUE;
            case UINT16 -> 0xFFFF;
            case INT32  -> Integer.MAX_VALUE;
            case UINT32 -> 0xFFFF_FFFFL;
            case FLOAT, DOUBLE -> Double.POSITIVE_INFINITY;
        };
    }

    /**
     * The minimum representable value for integer types.
     * Returns {@link Double#NEGATIVE_INFINITY} for floating-point types.
     */
    public double minValue() {
        return switch (this) {
            case BIT, UINT8, UINT16, UINT32 -> 0;
            case INT8   -> Byte.MIN_VALUE;
            case INT16  -> Short.MIN_VALUE;
            case INT32  -> Integer.MIN_VALUE;
            case FLOAT, DOUBLE -> Double.NEGATIVE_INFINITY;
        };
    }
}
