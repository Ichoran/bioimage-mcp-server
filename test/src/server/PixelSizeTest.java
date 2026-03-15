package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.PixelSize.LengthUnit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PixelSizeTest {

    @Test
    void convertMicrometersToNanometersIsExact() {
        var um = PixelSize.of(0.1, LengthUnit.MICROMETER);
        var nm = um.convertTo(LengthUnit.NANOMETER);
        // 0.1 µm = 100 nm exactly — no floating-point fuzz
        assertEquals(0, new BigDecimal("100").compareTo(nm.value()));
        assertEquals(LengthUnit.NANOMETER, nm.unit());
    }

    @Test
    void convertNanometersToMicrometersIsExact() {
        var nm = PixelSize.of(650.0, LengthUnit.NANOMETER);
        var um = nm.convertTo(LengthUnit.MICROMETER);
        assertEquals(0, new BigDecimal("0.650").compareTo(um.value()));
    }

    @Test
    void convertToSameUnitReturnsSameInstance() {
        var um = PixelSize.of(0.1, LengthUnit.MICROMETER);
        assertSame(um, um.convertTo(LengthUnit.MICROMETER));
    }

    @Test
    void roundTripConversionIsExact() {
        var original = PixelSize.of(0.325, LengthUnit.MICROMETER);
        var roundTripped = original
                .convertTo(LengthUnit.NANOMETER)
                .convertTo(LengthUnit.METER)
                .convertTo(LengthUnit.MICROMETER);
        // With BigDecimal this should be exactly equal, not just close
        assertEquals(0, original.value().compareTo(roundTripped.value()),
                "round-trip should be exact: " + original.value()
                        + " vs " + roundTripped.value());
    }

    @Test
    void typicalConfocalPixelSize() {
        // 0.10833 µm — a real-world value that causes trouble in double arithmetic
        var um = PixelSize.of(0.10833, LengthUnit.MICROMETER);
        var nm = um.convertTo(LengthUnit.NANOMETER);
        var backToUm = nm.convertTo(LengthUnit.MICROMETER);
        assertEquals(0, um.value().compareTo(backToUm.value()));
    }

    @Test
    void rejectsZero() {
        assertThrows(IllegalArgumentException.class,
                () -> PixelSize.of(0, LengthUnit.MICROMETER));
    }

    @Test
    void rejectsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> PixelSize.of(-1.0, LengthUnit.NANOMETER));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> new PixelSize(null, LengthUnit.MICROMETER));
    }

    @Test
    void toStringIsReadable() {
        var ps = PixelSize.of(0.325, LengthUnit.MICROMETER);
        assertEquals("0.325 µm", ps.toString());
    }
}
