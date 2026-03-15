package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class MaxProjectionTest {

    // ================================================================
    // UINT8
    // ================================================================

    @Test
    void uint8MaxAccumulation() {
        var proj = MaxProjection.create(PixelType.UINT8, 4);
        proj.addPlane(new byte[] { 10, 20, 30, 40 }, ByteOrder.BIG_ENDIAN);
        proj.addPlane(new byte[] { 40, 10, 50, 20 }, ByteOrder.BIG_ENDIAN);

        // Max should be [40, 20, 50, 40]
        byte[] result = proj.toUint8AutoContrast(0, 100);
        // With 0th and 100th percentile, the range is [20, 50]
        // 40 → (40-20)/(50-20) * 255 = 170
        // 20 → 0
        // 50 → 255
        // 40 → 170
        assertNotNull(result);
        assertEquals(4, result.length);
    }

    @Test
    void uint8ForkPreservesState() {
        var proj = MaxProjection.create(PixelType.UINT8, 3);
        proj.addPlane(new byte[] { 10, 20, 30 }, ByteOrder.BIG_ENDIAN);

        var forked = proj.fork();

        // Add more to original
        proj.addPlane(new byte[] { 50, 50, 50 }, ByteOrder.BIG_ENDIAN);

        // Fork should not see the second plane
        byte[] forkResult = forked.toUint8AutoContrast(0, 100);
        byte[] origResult = proj.toUint8AutoContrast(0, 100);

        // Fork: values [10, 20, 30], range [10, 30]
        // Orig: values [50, 50, 50], range [50, 50] → degenerate → all 0
        assertFalse(java.util.Arrays.equals(forkResult, origResult),
                "fork should differ from original after more planes added");
    }

    @Test
    void uint8MatchesDoublePathPercentile() {
        // Generate some realistic-ish data
        int nPixels = 256;
        byte[] plane1 = new byte[nPixels];
        byte[] plane2 = new byte[nPixels];
        for (int i = 0; i < nPixels; i++) {
            plane1[i] = (byte) (i % 200);
            plane2[i] = (byte) ((i * 7 + 13) % 256);
        }

        // Int path (MaxProjection)
        var intProj = MaxProjection.create(PixelType.UINT8, nPixels);
        intProj.addPlane(plane1, ByteOrder.BIG_ENDIAN);
        intProj.addPlane(plane2, ByteOrder.BIG_ENDIAN);
        byte[] intResult = intProj.toUint8AutoContrast(0.1, 99.9);

        // Double path (PixelConverter)
        double[] d1 = PixelConverter.toDoubles(
                plane1, PixelType.UINT8, ByteOrder.BIG_ENDIAN);
        double[] d2 = PixelConverter.toDoubles(
                plane2, PixelType.UINT8, ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < nPixels; i++) {
            d1[i] = Math.max(d1[i], d2[i]);
        }
        byte[] dblResult = PixelConverter.toUint8AutoContrast(d1, 0.1, 99.9);

        assertArrayEquals(dblResult, intResult,
                "int path should match double path exactly for uint8");
    }

    // ================================================================
    // UINT16
    // ================================================================

    @Test
    void uint16MaxAccumulation() {
        int nPixels = 3;
        byte[] p1 = shortBytes(new int[] { 100, 5000, 30000 },
                ByteOrder.LITTLE_ENDIAN);
        byte[] p2 = shortBytes(new int[] { 200, 4000, 40000 },
                ByteOrder.LITTLE_ENDIAN);

        var proj = MaxProjection.create(PixelType.UINT16, nPixels);
        proj.addPlane(p1, ByteOrder.LITTLE_ENDIAN);
        proj.addPlane(p2, ByteOrder.LITTLE_ENDIAN);

        byte[] result = proj.toUint8AutoContrast(0, 100);
        // Max should be [200, 5000, 40000]
        // With full range: 200→0(ish), 5000→32, 40000→255
        assertNotNull(result);
        assertEquals(3, result.length);
        // 40000 should map highest
        assertTrue(Byte.toUnsignedInt(result[2]) > Byte.toUnsignedInt(result[0]),
                "pixel with higher max should be brighter");
    }

    @Test
    void uint16MatchesDoublePathPercentile() {
        int nPixels = 512;
        byte[] plane1 = new byte[nPixels * 2];
        byte[] plane2 = new byte[nPixels * 2];
        var buf1 = ByteBuffer.wrap(plane1).order(ByteOrder.LITTLE_ENDIAN);
        var buf2 = ByteBuffer.wrap(plane2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < nPixels; i++) {
            buf1.putShort((short) (i * 100 % 60000));
            buf2.putShort((short) ((i * 137 + 42) % 65536));
        }

        // Int path
        var intProj = MaxProjection.create(PixelType.UINT16, nPixels);
        intProj.addPlane(plane1, ByteOrder.LITTLE_ENDIAN);
        intProj.addPlane(plane2, ByteOrder.LITTLE_ENDIAN);
        byte[] intResult = intProj.toUint8AutoContrast(0.1, 99.9);

        // Double path
        double[] d1 = PixelConverter.toDoubles(
                plane1, PixelType.UINT16, ByteOrder.LITTLE_ENDIAN);
        double[] d2 = PixelConverter.toDoubles(
                plane2, PixelType.UINT16, ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < nPixels; i++) {
            d1[i] = Math.max(d1[i], d2[i]);
        }
        byte[] dblResult = PixelConverter.toUint8AutoContrast(d1, 0.1, 99.9);

        assertArrayEquals(dblResult, intResult,
                "int path should match double path exactly for uint16");
    }

    // ================================================================
    // INT8 (signed)
    // ================================================================

    @Test
    void int8SignedMaxIsCorrect() {
        // -10 and 5: max should be 5
        // 120 and -120: max should be 120
        var proj = MaxProjection.create(PixelType.INT8, 2);
        proj.addPlane(new byte[] { -10, 120 }, ByteOrder.BIG_ENDIAN);
        proj.addPlane(new byte[] { 5, -120 }, ByteOrder.BIG_ENDIAN);

        byte[] result = proj.toUint8AutoContrast(0, 100);
        // Max values: [5, 120], range [5, 120]
        // 5 → 0, 120 → 255
        assertEquals(0, Byte.toUnsignedInt(result[0]));
        assertEquals(255, Byte.toUnsignedInt(result[1]));
    }

    @Test
    void int8MatchesDoublePathPercentile() {
        int nPixels = 256;
        byte[] plane1 = new byte[nPixels];
        byte[] plane2 = new byte[nPixels];
        for (int i = 0; i < nPixels; i++) {
            plane1[i] = (byte) (i - 128);  // -128 to 127
            plane2[i] = (byte) ((i * 3 + 7) % 256 - 128);
        }

        var intProj = MaxProjection.create(PixelType.INT8, nPixels);
        intProj.addPlane(plane1, ByteOrder.BIG_ENDIAN);
        intProj.addPlane(plane2, ByteOrder.BIG_ENDIAN);
        byte[] intResult = intProj.toUint8AutoContrast(0.1, 99.9);

        double[] d1 = PixelConverter.toDoubles(
                plane1, PixelType.INT8, ByteOrder.BIG_ENDIAN);
        double[] d2 = PixelConverter.toDoubles(
                plane2, PixelType.INT8, ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < nPixels; i++) {
            d1[i] = Math.max(d1[i], d2[i]);
        }
        byte[] dblResult = PixelConverter.toUint8AutoContrast(d1, 0.1, 99.9);

        assertArrayEquals(dblResult, intResult,
                "int path should match double path for int8");
    }

    // ================================================================
    // INT16 (signed)
    // ================================================================

    @Test
    void int16MatchesDoublePathPercentile() {
        int nPixels = 512;
        byte[] plane1 = new byte[nPixels * 2];
        byte[] plane2 = new byte[nPixels * 2];
        var buf1 = ByteBuffer.wrap(plane1).order(ByteOrder.BIG_ENDIAN);
        var buf2 = ByteBuffer.wrap(plane2).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < nPixels; i++) {
            buf1.putShort((short) (i * 100 - 25600));
            buf2.putShort((short) ((i * 137 + 42) % 65536 - 32768));
        }

        var intProj = MaxProjection.create(PixelType.INT16, nPixels);
        intProj.addPlane(plane1, ByteOrder.BIG_ENDIAN);
        intProj.addPlane(plane2, ByteOrder.BIG_ENDIAN);
        byte[] intResult = intProj.toUint8AutoContrast(0.1, 99.9);

        double[] d1 = PixelConverter.toDoubles(
                plane1, PixelType.INT16, ByteOrder.BIG_ENDIAN);
        double[] d2 = PixelConverter.toDoubles(
                plane2, PixelType.INT16, ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < nPixels; i++) {
            d1[i] = Math.max(d1[i], d2[i]);
        }
        byte[] dblResult = PixelConverter.toUint8AutoContrast(d1, 0.1, 99.9);

        assertArrayEquals(dblResult, intResult,
                "int path should match double path for int16");
    }

    // ================================================================
    // FLOAT (uses DoubleMaxProjection)
    // ================================================================

    @Test
    void floatMaxAccumulation() {
        int nPixels = 2;
        byte[] p1 = floatBytes(new float[] { 1.5f, 3.0f },
                ByteOrder.LITTLE_ENDIAN);
        byte[] p2 = floatBytes(new float[] { 2.5f, 1.0f },
                ByteOrder.LITTLE_ENDIAN);

        var proj = MaxProjection.create(PixelType.FLOAT, nPixels);
        proj.addPlane(p1, ByteOrder.LITTLE_ENDIAN);
        proj.addPlane(p2, ByteOrder.LITTLE_ENDIAN);

        byte[] result = proj.toUint8AutoContrast(0, 100);
        // Max: [2.5, 3.0], range [2.5, 3.0]
        // 2.5 → 0, 3.0 → 255
        assertEquals(0, Byte.toUnsignedInt(result[0]));
        assertEquals(255, Byte.toUnsignedInt(result[1]));
    }

    @Test
    void floatForkPreservesState() {
        int nPixels = 2;
        byte[] p1 = floatBytes(new float[] { 1.0f, 2.0f },
                ByteOrder.BIG_ENDIAN);

        var proj = MaxProjection.create(PixelType.FLOAT, nPixels);
        proj.addPlane(p1, ByteOrder.BIG_ENDIAN);
        var forked = proj.fork();

        byte[] p2 = floatBytes(new float[] { 10.0f, 10.0f },
                ByteOrder.BIG_ENDIAN);
        proj.addPlane(p2, ByteOrder.BIG_ENDIAN);

        byte[] forkResult = forked.toUint8AutoContrast(0, 100);
        byte[] origResult = proj.toUint8AutoContrast(0, 100);
        assertFalse(java.util.Arrays.equals(forkResult, origResult),
                "fork should not be affected by later addPlane");
    }

    // ================================================================
    // Factory dispatches to correct implementation
    // ================================================================

    @Test
    void factoryCreatesIntProjectionForSmallTypes() {
        for (var type : new PixelType[] {
                PixelType.BIT, PixelType.UINT8, PixelType.INT8,
                PixelType.UINT16, PixelType.INT16 }) {
            var proj = MaxProjection.create(type, 1);
            assertInstanceOf(MaxProjection.IntMaxProjection.class, proj,
                    type + " should use IntMaxProjection");
        }
    }

    @Test
    void factoryCreatesDoubleProjectionForLargeTypes() {
        for (var type : new PixelType[] {
                PixelType.INT32, PixelType.UINT32,
                PixelType.FLOAT, PixelType.DOUBLE }) {
            var proj = MaxProjection.create(type, 1);
            assertInstanceOf(MaxProjection.DoubleMaxProjection.class, proj,
                    type + " should use DoubleMaxProjection");
        }
    }

    // ================================================================
    // Single plane produces valid output
    // ================================================================

    @Test
    void singlePlaneUint8() {
        var proj = MaxProjection.create(PixelType.UINT8, 3);
        proj.addPlane(new byte[] { 0, (byte) 128, (byte) 255 },
                ByteOrder.BIG_ENDIAN);

        byte[] result = proj.toUint8AutoContrast(0, 100);
        assertEquals(0, Byte.toUnsignedInt(result[0]));
        assertEquals(128, Byte.toUnsignedInt(result[1]));
        assertEquals(255, Byte.toUnsignedInt(result[2]));
    }

    @Test
    void uniformPlaneProducesZeros() {
        // All pixels the same → degenerate range → all 0
        var proj = MaxProjection.create(PixelType.UINT8, 4);
        proj.addPlane(new byte[] { 42, 42, 42, 42 }, ByteOrder.BIG_ENDIAN);

        byte[] result = proj.toUint8AutoContrast(0, 100);
        for (byte b : result) {
            assertEquals(0, Byte.toUnsignedInt(b));
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static byte[] shortBytes(int[] values, ByteOrder order) {
        var buf = ByteBuffer.allocate(values.length * 2).order(order);
        for (int v : values) buf.putShort((short) v);
        return buf.array();
    }

    private static byte[] floatBytes(float[] values, ByteOrder order) {
        var buf = ByteBuffer.allocate(values.length * 4).order(order);
        for (float v : values) buf.putFloat(v);
        return buf.array();
    }
}
