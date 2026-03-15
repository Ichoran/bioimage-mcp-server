package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class StatsAccumulatorTest {

    // ================================================================
    // ExactAccumulator — uint8
    // ================================================================

    @Test
    void uint8BasicStats() {
        var acc = StatsAccumulator.create(0, PixelType.UINT8);
        // Values: 10, 20, 30, 40, 50
        acc.addPlane(new byte[] { 10, 20, 30, 40, 50 }, ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(256, false, 1.0);

        assertEquals(0, stats.channel());
        assertEquals(10.0, stats.min());
        assertEquals(50.0, stats.max());
        assertEquals(30.0, stats.mean(), 1e-10);
        assertEquals(30.0, stats.median());
        // stddev = sqrt(((20^2 + 10^2 + 0 + 10^2 + 20^2) / 5)) = sqrt(200)
        assertEquals(Math.sqrt(200.0), stats.stddev(), 1e-10);
        assertFalse(stats.sampled());
        assertEquals(1.0, stats.sampledFraction());
    }

    @Test
    void uint8AllSameValue() {
        var acc = StatsAccumulator.create(0, PixelType.UINT8);
        acc.addPlane(new byte[] { 42, 42, 42, 42 }, ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(42.0, stats.min());
        assertEquals(42.0, stats.max());
        assertEquals(42.0, stats.mean());
        assertEquals(42.0, stats.median());
        assertEquals(0.0, stats.stddev());
        // Degenerate histogram: one bin at [42, 42]
        assertEquals(1, stats.histogramCounts().length);
        assertEquals(4, stats.histogramCounts()[0]);
    }

    @Test
    void uint8Saturation() {
        var acc = StatsAccumulator.create(0, PixelType.UINT8);
        // 2 pixels at 0 (type min), 2 at 255 (type max), 6 in between
        acc.addPlane(new byte[] { 0, 0, (byte) 255, (byte) 255,
                                  50, 100, 100, 100, (byte) 150, (byte) 200 },
                     ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(256, false, 1.0);

        assertEquals(0.2, stats.saturationFractionLow(), 1e-10);
        assertEquals(0.2, stats.saturationFractionHigh(), 1e-10);
    }

    @Test
    void uint8BitDepthUtilization() {
        var acc = StatsAccumulator.create(0, PixelType.UINT8);
        // Range 100-200 out of 0-255
        acc.addPlane(new byte[] { 100, (byte) 150, (byte) 200 },
                     ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        // utilization = (200-100) / (255-0) = 100/255
        assertEquals(100.0 / 255.0, stats.bitDepthUtilization(), 1e-10);
    }

    @Test
    void uint8MultiplePlanes() {
        var acc = StatsAccumulator.create(0, PixelType.UINT8);
        acc.addPlane(new byte[] { 10, 20 }, ByteOrder.BIG_ENDIAN);
        acc.addPlane(new byte[] { 30, 40 }, ByteOrder.BIG_ENDIAN);

        var stats = acc.finish(256, false, 1.0);
        assertEquals(4, acc.count());
        assertEquals(10.0, stats.min());
        assertEquals(40.0, stats.max());
        assertEquals(25.0, stats.mean(), 1e-10);
    }

    @Test
    void uint8ExactMedianEvenCount() {
        var acc = StatsAccumulator.create(0, PixelType.UINT8);
        // Values: 10, 20, 30, 40 → median = (20+30)/2 = 25
        acc.addPlane(new byte[] { 10, 20, 30, 40 }, ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(256, false, 1.0);
        assertEquals(25.0, stats.median(), 1e-10);
    }

    @Test
    void uint8HistogramBinning() {
        var acc = StatsAccumulator.create(0, PixelType.UINT8);
        // 5 bins over range [0, 100], values at 0, 25, 50, 75, 100
        acc.addPlane(new byte[] { 0, 25, 50, 75, 100 }, ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(5, false, 1.0);

        assertEquals(6, stats.histogramBinEdges().length);
        assertEquals(5, stats.histogramCounts().length);
        assertEquals(0.0, stats.histogramBinEdges()[0]);
        assertEquals(100.0, stats.histogramBinEdges()[5]);

        // Total should be 5
        long total = 0;
        for (long c : stats.histogramCounts()) total += c;
        assertEquals(5, total);
    }

    // ================================================================
    // ExactAccumulator — int8 (signed)
    // ================================================================

    @Test
    void int8SignedValues() {
        var acc = StatsAccumulator.create(0, PixelType.INT8);
        // -128, -1, 0, 1, 127
        acc.addPlane(new byte[] { (byte) -128, (byte) -1, 0, 1, 127 },
                     ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(-128.0, stats.min());
        assertEquals(127.0, stats.max());
        // mean = (-128 + -1 + 0 + 1 + 127) / 5 = -1/5 = -0.2
        assertEquals(-0.2, stats.mean(), 1e-10);
        assertEquals(0.0, stats.median());
    }

    @Test
    void int8SaturationAtTypeBounds() {
        var acc = StatsAccumulator.create(0, PixelType.INT8);
        acc.addPlane(new byte[] { (byte) -128, (byte) -128, 0, 127, 127 },
                     ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(0.4, stats.saturationFractionLow(), 1e-10);
        assertEquals(0.4, stats.saturationFractionHigh(), 1e-10);
    }

    // ================================================================
    // ExactAccumulator — uint16
    // ================================================================

    @Test
    void uint16BasicStats() {
        var acc = StatsAccumulator.create(1, PixelType.UINT16);
        var buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 100);
        buf.putShort((short) 1000);
        buf.putShort((short) 10000);

        acc.addPlane(buf.array(), ByteOrder.LITTLE_ENDIAN);
        var stats = acc.finish(100, false, 1.0);

        assertEquals(1, stats.channel());
        assertEquals(100.0, stats.min());
        assertEquals(10000.0, stats.max());
        assertEquals(1000.0, stats.median());
    }

    @Test
    void uint16BigEndian() {
        var acc = StatsAccumulator.create(0, PixelType.UINT16);
        var buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) 500);
        buf.putShort((short) 1500);

        acc.addPlane(buf.array(), ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(500.0, stats.min());
        assertEquals(1500.0, stats.max());
        assertEquals(1000.0, stats.mean(), 1e-10);
    }

    @Test
    void int16SignedValues() {
        var acc = StatsAccumulator.create(0, PixelType.INT16);
        var buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) -1000);
        buf.putShort((short) 0);
        buf.putShort((short) 1000);

        acc.addPlane(buf.array(), ByteOrder.LITTLE_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(-1000.0, stats.min());
        assertEquals(1000.0, stats.max());
        assertEquals(0.0, stats.mean(), 1e-10);
        assertEquals(0.0, stats.median());
    }

    // ================================================================
    // DigestAccumulator — int32
    // ================================================================

    @Test
    void int32BasicStats() {
        var acc = StatsAccumulator.create(0, PixelType.INT32);
        var buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(100);
        buf.putInt(200);
        buf.putInt(300);
        buf.putInt(400);
        buf.putInt(500);

        acc.addPlane(buf.array(), ByteOrder.LITTLE_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(100.0, stats.min());
        assertEquals(500.0, stats.max());
        assertEquals(300.0, stats.mean(), 1e-10);
        assertEquals(300.0, stats.median(), 10.0);
    }

    @Test
    void uint32LargeValues() {
        var acc = StatsAccumulator.create(0, PixelType.UINT32);
        var buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0);
        buf.putInt(-1);  // 0xFFFFFFFF = 4294967295

        acc.addPlane(buf.array(), ByteOrder.LITTLE_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(0.0, stats.min());
        assertEquals(4294967295.0, stats.max());
    }

    // ================================================================
    // DigestAccumulator — float
    // ================================================================

    @Test
    void floatBasicStats() {
        var acc = StatsAccumulator.create(0, PixelType.FLOAT);
        var buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buf.putFloat(1.5f);
        buf.putFloat(2.5f);
        buf.putFloat(3.5f);
        buf.putFloat(4.5f);
        buf.putFloat(5.5f);

        acc.addPlane(buf.array(), ByteOrder.LITTLE_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(1.5, stats.min(), 1e-6);
        assertEquals(5.5, stats.max(), 1e-6);
        assertEquals(3.5, stats.mean(), 1e-6);
        assertEquals(3.5, stats.median(), 0.5);
        assertTrue(Double.isNaN(stats.bitDepthUtilization()));
    }

    @Test
    void floatNoSaturation() {
        // Float has infinite range, so no saturation detection
        var acc = StatsAccumulator.create(0, PixelType.FLOAT);
        var buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putFloat(0.0f);
        buf.putFloat(1.0f);

        acc.addPlane(buf.array(), ByteOrder.LITTLE_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(0.0, stats.saturationFractionLow());
        assertEquals(0.0, stats.saturationFractionHigh());
    }

    // ================================================================
    // DigestAccumulator — double
    // ================================================================

    @Test
    void doubleBasicStats() {
        var acc = StatsAccumulator.create(0, PixelType.DOUBLE);
        var buf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        buf.putDouble(Math.PI);
        buf.putDouble(Math.E);
        buf.putDouble(1.0);

        acc.addPlane(buf.array(), ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(1.0, stats.min(), 1e-15);
        assertEquals(Math.PI, stats.max(), 1e-15);
        double expectedMean = (Math.PI + Math.E + 1.0) / 3.0;
        assertEquals(expectedMean, stats.mean(), 1e-10);
    }

    // ================================================================
    // Multi-plane accumulation
    // ================================================================

    @Test
    void multiplePlanesAccumulateCorrectly() {
        var acc = StatsAccumulator.create(0, PixelType.UINT8);
        acc.addPlane(new byte[] { 0, 10 }, ByteOrder.BIG_ENDIAN);
        acc.addPlane(new byte[] { 20, 30 }, ByteOrder.BIG_ENDIAN);
        acc.addPlane(new byte[] { 40, 50 }, ByteOrder.BIG_ENDIAN);

        var stats = acc.finish(256, false, 1.0);

        assertEquals(6, acc.count());
        assertEquals(0.0, stats.min());
        assertEquals(50.0, stats.max());
        assertEquals(25.0, stats.mean(), 1e-10);
        // Median of {0,10,20,30,40,50} = (20+30)/2 = 25
        assertEquals(25.0, stats.median(), 1e-10);
    }

    @Test
    void multiplePlanesFloat() {
        var acc = StatsAccumulator.create(0, PixelType.FLOAT);
        var buf1 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf1.putFloat(1.0f);
        buf1.putFloat(2.0f);

        var buf2 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf2.putFloat(3.0f);
        buf2.putFloat(4.0f);

        acc.addPlane(buf1.array(), ByteOrder.LITTLE_ENDIAN);
        acc.addPlane(buf2.array(), ByteOrder.LITTLE_ENDIAN);

        var stats = acc.finish(10, false, 1.0);

        assertEquals(1.0, stats.min(), 1e-6);
        assertEquals(4.0, stats.max(), 1e-6);
        assertEquals(2.5, stats.mean(), 1e-6);
    }

    // ================================================================
    // Digest accuracy on larger data
    // ================================================================

    @Test
    void digestMedianAccurateOnUniformData() {
        // Uniform distribution 0..9999 as int32
        var acc = StatsAccumulator.create(0, PixelType.INT32);
        int n = 10000;
        var buf = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            buf.putInt(i);
        }

        acc.addPlane(buf.array(), ByteOrder.LITTLE_ENDIAN);
        var stats = acc.finish(100, false, 1.0);

        assertEquals(0.0, stats.min());
        assertEquals(9999.0, stats.max());
        assertEquals(4999.5, stats.mean(), 1e-6);
        // t-digest should give a very accurate median for uniform data
        assertEquals(4999.5, stats.median(), 100.0);
    }

    @Test
    void digestMedianAccurateOnLargeData() {
        // 3x the buffer size to ensure digest has compressed internally
        var acc = StatsAccumulator.create(0, PixelType.INT32);
        int n = 30000;
        var buf = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            buf.putInt(i);
        }

        acc.addPlane(buf.array(), ByteOrder.LITTLE_ENDIAN);
        var stats = acc.finish(100, false, 1.0);

        assertEquals((n - 1.0) / 2.0, stats.mean(), 1e-6);
        // Median within 2% of true value
        assertEquals((n - 1.0) / 2.0, stats.median(), n * 0.02);
    }

    // ================================================================
    // Histogram properties
    // ================================================================

    @Test
    void histogramCountsSumToPixelCount() {
        var acc = StatsAccumulator.create(0, PixelType.UINT16);
        int n = 1000;
        var buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            buf.putShort((short) (i % 256));
        }

        acc.addPlane(buf.array(), ByteOrder.LITTLE_ENDIAN);
        var stats = acc.finish(50, false, 1.0);

        long total = 0;
        for (long c : stats.histogramCounts()) total += c;
        assertEquals(n, total);
    }

    @Test
    void histogramEdgesSpanDataRange() {
        var acc = StatsAccumulator.create(0, PixelType.UINT8);
        acc.addPlane(new byte[] { 20, 50, 80 }, ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(20.0, stats.histogramBinEdges()[0]);
        assertEquals(80.0, stats.histogramBinEdges()[
                stats.histogramBinEdges().length - 1]);
    }

    @Test
    void digestHistogramCountsSumToPixelCount() {
        var acc = StatsAccumulator.create(0, PixelType.INT32);
        int n = 5000;
        var buf = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            buf.putInt(i);
        }

        acc.addPlane(buf.array(), ByteOrder.LITTLE_ENDIAN);
        var stats = acc.finish(50, false, 1.0);

        long total = 0;
        for (long c : stats.histogramCounts()) total += c;
        // Rounding correction ensures exact count match
        assertEquals(n, total);
    }

    // ================================================================
    // Edge cases
    // ================================================================

    @Test
    void singlePixel() {
        var acc = StatsAccumulator.create(0, PixelType.UINT8);
        acc.addPlane(new byte[] { 42 }, ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(42.0, stats.min());
        assertEquals(42.0, stats.max());
        assertEquals(42.0, stats.mean());
        assertEquals(42.0, stats.median());
        assertEquals(0.0, stats.stddev());
    }

    @Test
    void noDataThrows() {
        var acc = StatsAccumulator.create(0, PixelType.UINT8);
        assertThrows(IllegalStateException.class,
                () -> acc.finish(10, false, 1.0));
    }

    @Test
    void noDataThrowsDigest() {
        var acc = StatsAccumulator.create(0, PixelType.FLOAT);
        assertThrows(IllegalStateException.class,
                () -> acc.finish(10, false, 1.0));
    }

    @Test
    void bitType() {
        var acc = StatsAccumulator.create(0, PixelType.BIT);
        acc.addPlane(new byte[] { 0, 1, 1, 0, 1 }, ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(2, false, 1.0);

        assertEquals(0.0, stats.min());
        assertEquals(1.0, stats.max());
        assertEquals(0.6, stats.mean(), 1e-10);
    }

    @Test
    void sampledFlagPassedThrough() {
        var acc = StatsAccumulator.create(0, PixelType.UINT8);
        acc.addPlane(new byte[] { 10, 20 }, ByteOrder.BIG_ENDIAN);
        var stats = acc.finish(10, true, 0.5);

        assertTrue(stats.sampled());
        assertEquals(0.5, stats.sampledFraction());
    }

    @Test
    void singlePixelDigest() {
        var acc = StatsAccumulator.create(0, PixelType.INT32);
        var buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(42);

        acc.addPlane(buf.array(), ByteOrder.LITTLE_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(42.0, stats.min());
        assertEquals(42.0, stats.max());
        assertEquals(42.0, stats.mean());
        assertEquals(42.0, stats.median());
        assertEquals(0.0, stats.stddev());
    }

    @Test
    void allSameValueDigest() {
        var acc = StatsAccumulator.create(0, PixelType.FLOAT);
        var buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.putFloat(7.0f);
        buf.putFloat(7.0f);
        buf.putFloat(7.0f);
        buf.putFloat(7.0f);

        acc.addPlane(buf.array(), ByteOrder.LITTLE_ENDIAN);
        var stats = acc.finish(10, false, 1.0);

        assertEquals(7.0, stats.min(), 1e-6);
        assertEquals(7.0, stats.max(), 1e-6);
        assertEquals(7.0, stats.mean(), 1e-6);
        assertEquals(7.0, stats.median(), 1e-6);
        assertEquals(1, stats.histogramCounts().length);
        assertEquals(4, stats.histogramCounts()[0]);
    }

    // ================================================================
    // Factory dispatch
    // ================================================================

    @Test
    void factoryCreatesExactForNarrowTypes() {
        for (var type : new PixelType[] {
                PixelType.BIT, PixelType.UINT8, PixelType.INT8,
                PixelType.UINT16, PixelType.INT16 }) {
            var acc = StatsAccumulator.create(0, type);
            assertInstanceOf(StatsAccumulator.ExactAccumulator.class, acc,
                    "expected ExactAccumulator for " + type);
        }
    }

    @Test
    void factoryCreatesDigestForWideTypes() {
        for (var type : new PixelType[] {
                PixelType.INT32, PixelType.UINT32,
                PixelType.FLOAT, PixelType.DOUBLE }) {
            var acc = StatsAccumulator.create(0, type);
            assertInstanceOf(StatsAccumulator.DigestAccumulator.class, acc,
                    "expected DigestAccumulator for " + type);
        }
    }
}
