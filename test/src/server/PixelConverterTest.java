package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class PixelConverterTest {

    // ---- toDoubles: UINT8 ----

    @Test
    void uint8ValuesAreUnsigned() {
        var raw = new byte[] { 0, 127, (byte) 128, (byte) 255 };
        var values = PixelConverter.toDoubles(raw, PixelType.UINT8, ByteOrder.LITTLE_ENDIAN);
        assertArrayEquals(new double[] { 0, 127, 128, 255 }, values);
    }

    // ---- toDoubles: INT8 ----

    @Test
    void int8ValuesAreSigned() {
        var raw = new byte[] { 0, 127, (byte) 128, (byte) 255 };
        var values = PixelConverter.toDoubles(raw, PixelType.INT8, ByteOrder.LITTLE_ENDIAN);
        assertArrayEquals(new double[] { 0, 127, -128, -1 }, values);
    }

    // ---- toDoubles: UINT16 ----

    @Test
    void uint16LittleEndian() {
        var buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0);
        buf.putShort((short) -1);  // 0xFFFF = 65535 unsigned
        var values = PixelConverter.toDoubles(buf.array(), PixelType.UINT16, ByteOrder.LITTLE_ENDIAN);
        assertArrayEquals(new double[] { 0, 65535 }, values);
    }

    @Test
    void uint16BigEndian() {
        var buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) 1000);
        buf.putShort((short) 30000);
        var values = PixelConverter.toDoubles(buf.array(), PixelType.UINT16, ByteOrder.BIG_ENDIAN);
        assertArrayEquals(new double[] { 1000, 30000 }, values);
    }

    // ---- toDoubles: INT16 ----

    @Test
    void int16ValuesAreSigned() {
        var buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 1000);
        buf.putShort((short) -1000);
        var values = PixelConverter.toDoubles(buf.array(), PixelType.INT16, ByteOrder.LITTLE_ENDIAN);
        assertArrayEquals(new double[] { 1000, -1000 }, values);
    }

    // ---- toDoubles: UINT32 ----

    @Test
    void uint32ValuesAreUnsigned() {
        var buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0);
        buf.putInt(-1);  // 0xFFFFFFFF = 4294967295 unsigned
        var values = PixelConverter.toDoubles(buf.array(), PixelType.UINT32, ByteOrder.LITTLE_ENDIAN);
        assertArrayEquals(new double[] { 0, 4294967295.0 }, values);
    }

    // ---- toDoubles: FLOAT ----

    @Test
    void floatValues() {
        var buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putFloat(1.5f);
        buf.putFloat(-3.14f);
        var values = PixelConverter.toDoubles(buf.array(), PixelType.FLOAT, ByteOrder.LITTLE_ENDIAN);
        assertEquals(1.5, values[0], 1e-6);
        assertEquals(-3.14, values[1], 1e-2);
    }

    // ---- toDoubles: DOUBLE ----

    @Test
    void doubleValues() {
        var buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        buf.putDouble(Math.PI);
        buf.putDouble(-Math.E);
        var values = PixelConverter.toDoubles(buf.array(), PixelType.DOUBLE, ByteOrder.BIG_ENDIAN);
        assertEquals(Math.PI, values[0], 1e-15);
        assertEquals(-Math.E, values[1], 1e-15);
    }

    // ---- toUint8: linear mapping ----

    @Test
    void mapsRangeToZeroTo255() {
        var values = new double[] { 0, 50, 100 };
        var result = PixelConverter.toUint8(values, 0, 100);
        assertEquals(0, Byte.toUnsignedInt(result[0]));
        // 50/100 * 255 = 127.5; floating-point gives 127 or 128
        int mid = Byte.toUnsignedInt(result[1]);
        assertTrue(mid == 127 || mid == 128, "mid should be 127 or 128, got " + mid);
        assertEquals(255, Byte.toUnsignedInt(result[2]));
    }

    @Test
    void clampsOutOfRangeValues() {
        var values = new double[] { -10, 50, 200 };
        var result = PixelConverter.toUint8(values, 0, 100);
        assertEquals(0, Byte.toUnsignedInt(result[0]));    // clamped low
        assertEquals(255, Byte.toUnsignedInt(result[2]));  // clamped high
    }

    @Test
    void degenerateRangeProducesZeros() {
        var values = new double[] { 5, 5, 5 };
        var result = PixelConverter.toUint8(values, 5, 5);
        assertEquals(0, Byte.toUnsignedInt(result[0]));
        assertEquals(0, Byte.toUnsignedInt(result[1]));
    }

    // ---- toUint8FullRange ----

    @Test
    void fullRangeUint8() {
        var values = new double[] { 0, 128, 255 };
        var result = PixelConverter.toUint8FullRange(values, PixelType.UINT8);
        assertEquals(0, Byte.toUnsignedInt(result[0]));
        assertEquals(128, Byte.toUnsignedInt(result[1]));
        assertEquals(255, Byte.toUnsignedInt(result[2]));
    }

    @Test
    void fullRangeUint16() {
        var values = new double[] { 0, 32768, 65535 };
        var result = PixelConverter.toUint8FullRange(values, PixelType.UINT16);
        assertEquals(0, Byte.toUnsignedInt(result[0]));
        assertEquals(128, Byte.toUnsignedInt(result[1]));
        assertEquals(255, Byte.toUnsignedInt(result[2]));
    }

    @Test
    void fullRangeFloatUsesActualDataRange() {
        // Float type has infinite range, so falls back to data min/max
        var values = new double[] { 10, 20, 30 };
        var result = PixelConverter.toUint8FullRange(values, PixelType.FLOAT);
        assertEquals(0, Byte.toUnsignedInt(result[0]));
        assertEquals(128, Byte.toUnsignedInt(result[1]));
        assertEquals(255, Byte.toUnsignedInt(result[2]));
    }

    // ---- toUint8AutoContrast ----

    @Test
    void autoContrastStretchesPercentileRange() {
        // 100 values from 0 to 99
        var values = new double[100];
        for (int i = 0; i < 100; i++) values[i] = i;

        var result = PixelConverter.toUint8AutoContrast(values, 10, 90);

        // Value at percentile 10 (≈10) should map near 0
        // Value at percentile 90 (≈90) should map near 255
        // Value at percentile 50 (≈50) should map near 128
        assertTrue(Byte.toUnsignedInt(result[50]) > 100);
        assertTrue(Byte.toUnsignedInt(result[50]) < 160);
        // Values below the 10th percentile are clamped to 0
        assertEquals(0, Byte.toUnsignedInt(result[0]));
    }

    // ---- percentile ----

    @Test
    void percentileOfSingleValue() {
        assertEquals(42.0, PixelConverter.percentile(new double[] { 42.0 }, 50));
    }

    @Test
    void percentileZeroIsMin() {
        var values = new double[] { 10, 20, 30, 40, 50 };
        assertEquals(10, PixelConverter.percentile(values, 0));
    }

    @Test
    void percentileHundredIsMax() {
        var values = new double[] { 10, 20, 30, 40, 50 };
        assertEquals(50, PixelConverter.percentile(values, 100));
    }

    @Test
    void percentile50IsMedian() {
        var values = new double[] { 10, 20, 30, 40, 50 };
        assertEquals(30, PixelConverter.percentile(values, 50));
    }

    @Test
    void percentileInterpolates() {
        var values = new double[] { 0, 100 };
        assertEquals(25, PixelConverter.percentile(values, 25));
        assertEquals(75, PixelConverter.percentile(values, 75));
    }

    @Test
    void percentileDoesNotModifyInput() {
        var values = new double[] { 50, 10, 40, 20, 30 };
        var original = values.clone();
        PixelConverter.percentile(values, 50);
        assertArrayEquals(original, values);
    }

    @Test
    void percentileRejectsEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> PixelConverter.percentile(new double[0], 50));
    }

    @Test
    void percentileRejectsOutOfRange() {
        var values = new double[] { 1 };
        assertThrows(IllegalArgumentException.class,
                () -> PixelConverter.percentile(values, -1));
        assertThrows(IllegalArgumentException.class,
                () -> PixelConverter.percentile(values, 101));
    }

    // ---- Round-trip: raw bytes → doubles → uint8 ----

    @Test
    void roundTripUint16Gradient() {
        // 256 pixels forming a gradient from 0 to 65535
        int n = 256;
        var buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            buf.putShort((short) (i * 257));  // 0, 257, 514, ..., 65535
        }

        var values = PixelConverter.toDoubles(buf.array(), PixelType.UINT16, ByteOrder.LITTLE_ENDIAN);
        assertEquals(n, values.length);
        assertEquals(0, values[0]);
        assertEquals(65535, values[n - 1]);

        var uint8 = PixelConverter.toUint8FullRange(values, PixelType.UINT16);
        assertEquals(0, Byte.toUnsignedInt(uint8[0]));
        assertEquals(255, Byte.toUnsignedInt(uint8[n - 1]));
        // Middle value should be roughly 128
        int mid = Byte.toUnsignedInt(uint8[n / 2]);
        assertTrue(mid >= 126 && mid <= 130, "mid should be ~128, got " + mid);
    }
}
