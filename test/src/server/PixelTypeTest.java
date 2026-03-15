package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PixelTypeTest {

    @Test
    void unsignedIntegerRangesMatchKnownValues() {
        assertEquals(0xFF, PixelType.UINT8.maxValue());
        assertEquals(0xFFFF, PixelType.UINT16.maxValue());
        assertEquals(0xFFFF_FFFFL, PixelType.UINT32.maxValue());
        assertEquals(0, PixelType.UINT8.minValue());
        assertEquals(0, PixelType.UINT16.minValue());
        assertEquals(0, PixelType.UINT32.minValue());
    }

    @Test
    void signedIntegerRangesAreSymmetricIsh() {
        // Signed integer types have |min| = max + 1
        assertEquals(127, PixelType.INT8.maxValue());
        assertEquals(-128, PixelType.INT8.minValue());
        assertEquals(32767, PixelType.INT16.maxValue());
        assertEquals(-32768, PixelType.INT16.minValue());
    }

    @Test
    void floatingPointRangesAreInfinite() {
        assertEquals(Double.POSITIVE_INFINITY, PixelType.FLOAT.maxValue());
        assertEquals(Double.NEGATIVE_INFINITY, PixelType.FLOAT.minValue());
        assertEquals(Double.POSITIVE_INFINITY, PixelType.DOUBLE.maxValue());
        assertEquals(Double.NEGATIVE_INFINITY, PixelType.DOUBLE.minValue());
    }

    @Test
    void bitTypeIsOnebit() {
        assertEquals(1, PixelType.BIT.bitsPerPixel());
        assertEquals(0, PixelType.BIT.minValue());
        assertEquals(1, PixelType.BIT.maxValue());
    }

    @Test
    void bitsPerPixelMatchesBytesTimesEight() {
        for (var pt : PixelType.values()) {
            if (pt == PixelType.BIT) continue;
            assertEquals(pt.bytesPerPixel() * 8, pt.bitsPerPixel(),
                    pt + ": bitsPerPixel should be 8 * bytesPerPixel");
        }
    }

    @Test
    void everyTypeHasMinLessThanOrEqualToMax() {
        for (var pt : PixelType.values()) {
            assertTrue(pt.minValue() <= pt.maxValue(),
                    pt + ": min should be <= max");
        }
    }

    @Test
    void unsignedTypesHaveZeroMin() {
        for (var pt : PixelType.values()) {
            if (!pt.isSigned()) {
                assertEquals(0, pt.minValue(), pt + ": unsigned types should have min 0");
            }
        }
    }

    @Test
    void bioFormatsOrdinalAlignment() {
        // Bio-Formats FormatTools defines: INT8=0, UINT8=1, INT16=2,
        // UINT16=3, INT32=4, UINT32=5, FLOAT=6, DOUBLE=7, BIT=8.
        // Our enum order must match so that ordinal-based conversion works.
        assertEquals(0, PixelType.INT8.ordinal());
        assertEquals(1, PixelType.UINT8.ordinal());
        assertEquals(2, PixelType.INT16.ordinal());
        assertEquals(3, PixelType.UINT16.ordinal());
        assertEquals(4, PixelType.INT32.ordinal());
        assertEquals(5, PixelType.UINT32.ordinal());
        assertEquals(6, PixelType.FLOAT.ordinal());
        assertEquals(7, PixelType.DOUBLE.ordinal());
        assertEquals(8, PixelType.BIT.ordinal());
    }
}
