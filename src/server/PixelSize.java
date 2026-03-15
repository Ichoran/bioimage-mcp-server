package lab.kerrr.mcpbio.bioimageserver;

import java.math.BigDecimal;

/**
 * A physical pixel size with its unit.
 *
 * <p>The value is stored as a {@link BigDecimal} to avoid floating-point
 * precision loss during unit conversions.  All conversions between
 * {@link LengthUnit} values are powers-of-ten multiplications, which
 * {@code BigDecimal} handles exactly.
 *
 * @param value the numeric size (must be positive)
 * @param unit  the unit of measurement (e.g. µm, nm)
 */
public record PixelSize(BigDecimal value, LengthUnit unit) {
    public PixelSize {
        if (value == null) {
            throw new IllegalArgumentException("pixel size value must not be null");
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "pixel size must be positive, got: " + value);
        }
    }

    /** Convenience constructor from a double. */
    public static PixelSize of(double value, LengthUnit unit) {
        return new PixelSize(BigDecimal.valueOf(value), unit);
    }

    /** Convert this size to a different unit. */
    public PixelSize convertTo(LengthUnit target) {
        if (unit == target) return this;
        int exponentShift = unit.exponent - target.exponent;
        return new PixelSize(value.scaleByPowerOfTen(exponentShift), target);
    }

    /** Returns the value as a double (for calculations that need it). */
    public double doubleValue() {
        return value.doubleValue();
    }

    @Override
    public String toString() {
        return value.toPlainString() + " " + unit.symbol;
    }

    /**
     * Length units commonly used in microscopy.
     * Each carries its SI exponent (power of 10 relative to meters)
     * so that conversions are exact power-of-ten shifts.
     */
    public enum LengthUnit {
        NANOMETER ("nm", -9),
        MICROMETER("µm", -6),
        MILLIMETER("mm", -3),
        CENTIMETER("cm", -2),
        METER     ("m",   0);

        public final String symbol;

        /** SI exponent: 10^exponent meters. */
        public final int exponent;

        LengthUnit(String symbol, int exponent) {
            this.symbol = symbol;
            this.exponent = exponent;
        }
    }
}
