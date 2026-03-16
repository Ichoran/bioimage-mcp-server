package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.FakeImageReader.FakeSeries;
import lab.kerrr.mcpbio.bioimageserver.GetIntensityStatsTool.Range;
import lab.kerrr.mcpbio.bioimageserver.GetIntensityStatsTool.StatsResult;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;
import lab.kerrr.mcpbio.bioimageserver.ToolResult.ErrorKind;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class GetIntensityStatsToolTest {

    // ================================================================
    // Basic success cases
    // ================================================================

    @Test
    void singleChannelReturnsOneStats() {
        var result = run("/image.tif", null, null, null, null);
        assertSuccess(result, sr -> {
            assertEquals(1, sr.perChannel().size());
            assertEquals(0, sr.perChannel().get(0).channel());
        });
    }

    @Test
    void multiChannelReturnsStatsPerChannel() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 1, 3, 1,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, null, null, 256,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(3, sr.perChannel().size());
            assertEquals(0, sr.perChannel().get(0).channel());
            assertEquals(1, sr.perChannel().get(1).channel());
            assertEquals(2, sr.perChannel().get(2).channel());
            // Resolved range should cover all channels
            assertEquals(0, sr.channels().start());
            assertEquals(2, sr.channels().end());
        });
    }

    @Test
    void specificChannelReturnsOnlyThatChannel() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 1, 3, 1,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, Range.of(2), null, null, 256,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(1, sr.perChannel().size());
            assertEquals(2, sr.perChannel().get(0).channel());
            assertEquals(2, sr.channels().start());
            assertEquals(2, sr.channels().end());
        });
    }

    // ================================================================
    // Stats are correct for known synthetic data
    // ================================================================

    @Test
    void uint8StatsMatchExpected() {
        // 4x4 image, 1 channel, 1 Z, 1 T → 16 pixels
        // Synthetic formula: (y*4 + x) mod 256
        // Values: 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15
        var result = run("/image.tif", null, null, null, null);
        assertSuccess(result, sr -> {
            var s = sr.perChannel().get(0);
            assertEquals(0.0, s.min());
            assertEquals(15.0, s.max());
            assertEquals(7.5, s.mean(), 1e-10);
            assertEquals(7.5, s.median(), 1e-10);
            assertFalse(s.sampled());
        });
    }

    @Test
    void differentChannelsHaveDifferentStats() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 1, 3, 1,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, null, null, 256,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            // Channel offsets differ (c*7), so means differ
            assertNotEquals(sr.perChannel().get(0).mean(),
                    sr.perChannel().get(1).mean());
        });
    }

    // ================================================================
    // Multi-Z accumulation
    // ================================================================

    @Test
    void allZSlicesAccumulated() {
        // 4x4 image, 1 channel, 4 Z, 1 T
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(4, 4, 4, 1, 1,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, null, null, 256,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            // 4 Z-slices * 16 pixels each = 64 pixels total
            var s = sr.perChannel().get(0);
            long histTotal = 0;
            for (long c : s.histogramCounts()) histTotal += c;
            assertEquals(64, histTotal);
            // Resolved Z range should be 0-3
            assertEquals(0, sr.zRange().start());
            assertEquals(3, sr.zRange().end());
        });
    }

    @Test
    void specificZSliceReadsOnlyThatSlice() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(4, 4, 4, 1, 1,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, Range.of(2), null, 256,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            long histTotal = 0;
            for (long c : sr.perChannel().get(0).histogramCounts())
                histTotal += c;
            assertEquals(16, histTotal);  // one 4x4 plane
            assertEquals(2, sr.zRange().start());
            assertEquals(2, sr.zRange().end());
        });
    }

    // ================================================================
    // Timepoint handling
    // ================================================================

    @Test
    void adaptiveReadsAllTimepointsWhenSmall() {
        // With multiple timepoints and generous budget, adaptive
        // reads all of them
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(4, 4, 1, 1, 5,
                        PixelType.UINT8))
                .build();
        var request = GetIntensityStatsTool.Request.of("/image.tif");
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            long histTotal = 0;
            for (long c : sr.perChannel().get(0).histogramCounts())
                histTotal += c;
            // All 5 timepoints × 1 Z × 16 pixels = 80
            assertEquals(80, histTotal);
            assertEquals(0, sr.tRange().start());
            assertEquals(4, sr.tRange().end());
            assertEquals(5, sr.timepointsUsed().length);
        });
    }

    @Test
    void specificTimepointReadsOnlyThat() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(4, 4, 1, 1, 5,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, null, Range.of(3), 256,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            long histTotal = 0;
            for (long c : sr.perChannel().get(0).histogramCounts())
                histTotal += c;
            assertEquals(16, histTotal);
            assertEquals(3, sr.tRange().start());
            assertEquals(3, sr.tRange().end());
        });
    }

    // ================================================================
    // Range support
    // ================================================================

    @Test
    void channelRangeSelectsSubset() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(8, 8, 1, 4, 1,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, Range.of(1, 2), null, null, 256,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(2, sr.perChannel().size());
            assertEquals(1, sr.perChannel().get(0).channel());
            assertEquals(2, sr.perChannel().get(1).channel());
            assertEquals(1, sr.channels().start());
            assertEquals(2, sr.channels().end());
        });
    }

    @Test
    void zRangeSelectsSubset() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(4, 4, 8, 1, 1,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, Range.of(2, 5), null, 256,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            // 4 Z-slices × 16 pixels = 64 pixels
            long histTotal = 0;
            for (long c : sr.perChannel().get(0).histogramCounts())
                histTotal += c;
            assertEquals(64, histTotal);
            assertEquals(2, sr.zRange().start());
            assertEquals(5, sr.zRange().end());
            assertEquals(4, sr.zSlicesUsed().length);
        });
    }

    @Test
    void tRangeSelectsSubset() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(4, 4, 1, 1, 10,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, null, Range.of(1, 3), 256,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            // 3 timepoints × 16 pixels = 48 pixels
            long histTotal = 0;
            for (long c : sr.perChannel().get(0).histogramCounts())
                histTotal += c;
            assertEquals(48, histTotal);
            assertEquals(1, sr.tRange().start());
            assertEquals(3, sr.tRange().end());
            assertEquals(3, sr.timepointsUsed().length);
        });
    }

    @Test
    void adaptiveStopsByByteBudget() {
        // 32x32, 1C, 10Z, 1T.  Each plane = 1024 bytes.
        // Budget = 3072 → can afford 3 planes.
        // Adaptive should read 3 out of 10 Z-slices.
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(32, 32, 10, 1, 1,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, null, null, 256,
                Duration.ofSeconds(5), 3072);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertTrue(sr.perChannel().get(0).sampled());
            assertEquals(3, sr.zSlicesUsed().length);
            // Reports full range even though only 3 read
            assertEquals(0, sr.zRange().start());
            assertEquals(9, sr.zRange().end());
        });
    }

    @Test
    void adaptiveVolumeReadsFullZBeforeStopping() {
        // 4x4, 1C, 4Z, 5T.  Both Z>1 and T>1 → volume mode.
        // Each plane = 16 bytes.  Volume = 4 planes = 64 bytes.
        // Budget = 100 bytes → fits 1 volume but not 2.
        // Should read exactly 1 full Z-stack (all 4 Z at t=0).
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(4, 4, 4, 1, 5,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, null, null, 256,
                Duration.ofSeconds(5), 100);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            // Should have read all 4 Z-slices
            assertEquals(4, sr.zSlicesUsed().length);
            // But only 1 timepoint
            assertEquals(1, sr.timepointsUsed().length);
            assertTrue(sr.perChannel().get(0).sampled());
        });
    }

    @Test
    void adaptiveVolumeMultiChannel() {
        // 4x4, 2C, 3Z, 4T.  Volume mode.
        // Each plane = 16 bytes.  Volume = 2C × 3Z = 6 planes = 96 bytes.
        // Budget = 250 bytes → fits 2 volumes but not 3.
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(4, 4, 3, 2, 4,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, null, null, 256,
                Duration.ofSeconds(5), 250);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(2, sr.perChannel().size());
            // All 3 Z-slices read
            assertEquals(3, sr.zSlicesUsed().length);
            // 2 timepoints fit (96 + 96 = 192 ≤ 250, 192 + 96 = 288 > 250)
            assertEquals(2, sr.timepointsUsed().length);
            assertTrue(sr.perChannel().get(0).sampled());
        });
    }

    @Test
    void resultIncludesPixelType() {
        var result = run("/image.tif", null, null, null, null);
        assertSuccess(result, sr -> {
            assertEquals(PixelType.UINT8, sr.pixelType());
        });
    }

    // ================================================================
    // Byte budget and subsampling
    // ================================================================

    @Test
    void subsamplesWhenOverBudget() {
        // 32x32 image, 1 channel, 10 Z, 1 T = 10 planes
        // Each plane = 32*32*1 = 1024 bytes
        // Budget: 3072 bytes → can afford 3 planes
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(32, 32, 10, 1, 1,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, null, null, 256,
                Duration.ofSeconds(5), 3072);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertTrue(sr.perChannel().get(0).sampled());
            assertTrue(sr.perChannel().get(0).sampledFraction() < 1.0);
            assertTrue(sr.perChannel().get(0).sampledFraction() > 0.0);
            // Should have subsampled Z
            assertTrue(sr.zSlicesUsed().length < 10);
        });
    }

    @Test
    void budgetTooSmallForOnePerChannelFails() {
        // 2 channels, each plane = 1024 bytes, budget = 500
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(32, 32, 1, 2, 1,
                        PixelType.UINT8))
                .build();
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, null, null, 256,
                Duration.ofSeconds(5), 500);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "budget");
    }

    // ================================================================
    // Histogram properties
    // ================================================================

    @Test
    void histogramBinsConfigurable() {
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, null, null, 50,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), simpleFactory());

        assertSuccess(result, sr -> {
            assertEquals(50,
                    sr.perChannel().get(0).histogramCounts().length);
            assertEquals(51,
                    sr.perChannel().get(0).histogramBinEdges().length);
        });
    }

    // ================================================================
    // Error cases
    // ================================================================

    @Test
    void accessDenied() {
        PathValidator deny = path -> new AccessResult.Denied("nope");
        var request = GetIntensityStatsTool.Request.of("/secret.tif");
        var result = GetIntensityStatsTool.execute(
                request, deny, simpleFactory());
        assertFailure(result, ErrorKind.ACCESS_DENIED, "nope");
    }

    @Test
    void channelOutOfRange() {
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, Range.of(5), null, null, 256,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), simpleFactory());
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "channel");
    }

    @Test
    void zOutOfRange() {
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, Range.of(99), null, 256,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), simpleFactory());
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "z");
    }

    @Test
    void timepointOutOfRange() {
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, null, null, Range.of(99), 256,
                Duration.ofSeconds(5), 512L * 1024 * 1024);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), simpleFactory());
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "timepoint");
    }

    @Test
    void negativeIndexResolvesToEnd() {
        var r = Range.of(-1);
        var resolved = r.resolve(10, "test");
        assertEquals(9, resolved.start());
        assertEquals(9, resolved.end());
    }

    @Test
    void negativeRangeResolvesFromEnd() {
        var r = Range.of(-5, -1);
        var resolved = r.resolve(10, "test");
        assertEquals(5, resolved.start());
        assertEquals(9, resolved.end());
    }

    @Test
    void negativeStartOutOfBoundsRejected() {
        var r = Range.of(-11);
        assertThrows(IllegalArgumentException.class,
                () -> r.resolve(10, "test"));
    }

    @Test
    void invertedRangeRejectedAtResolution() {
        var r = Range.of(5, 2);
        assertThrows(IllegalArgumentException.class,
                () -> r.resolve(10, "test"));
    }

    // ================================================================
    // Subsampling helpers
    // ================================================================

    @Test
    void evenlySpacedSelectsEndpoints() {
        int[] source = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
        int[] result = GetIntensityStatsTool.evenlySpaced(source, 3);
        assertEquals(3, result.length);
        assertEquals(0, result[0]);  // first
        assertEquals(5, result[1]);  // middle
        assertEquals(9, result[2]);  // last
    }

    @Test
    void evenlySpacedReturnsAllWhenCountExceedsLength() {
        int[] source = { 10, 20, 30 };
        int[] result = GetIntensityStatsTool.evenlySpaced(source, 10);
        assertArrayEquals(source, result);
    }

    @Test
    void evenlySpacedSingleReturnsMidpoint() {
        int[] source = { 0, 1, 2, 3, 4 };
        int[] result = GetIntensityStatsTool.evenlySpaced(source, 1);
        assertEquals(1, result.length);
        assertEquals(2, result[0]);
    }

    // ================================================================
    // Different pixel types
    // ================================================================

    @Test
    void uint16ProducesCorrectStats() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(8, 8, 1, 1, 1,
                        PixelType.UINT16))
                .build();
        var request = GetIntensityStatsTool.Request.of("/image.tif");
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            var s = sr.perChannel().get(0);
            assertTrue(s.min() >= 0);
            assertTrue(s.max() <= 65535);
            assertTrue(s.mean() >= s.min());
            assertTrue(s.mean() <= s.max());
        });
    }

    @Test
    void int32ProducesCorrectStats() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(8, 8, 1, 1, 1,
                        PixelType.INT32))
                .build();
        var request = GetIntensityStatsTool.Request.of("/image.tif");
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            var s = sr.perChannel().get(0);
            assertTrue(s.mean() >= s.min());
            assertTrue(s.mean() <= s.max());
            assertFalse(s.sampled());
        });
    }

    // ================================================================
    // Helpers
    // ================================================================

    private ToolResult<StatsResult> run(
            String path, Range channels, Range zRange,
            Range tRange, Integer histogramBins) {
        var request = GetIntensityStatsTool.Request.of(
                path, null, channels, zRange, tRange,
                histogramBins, Duration.ofSeconds(5), null);
        return GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), simpleFactory());
    }

    private static Supplier<ImageReader> simpleFactory() {
        return () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(4, 4, 1, 1, 1,
                        PixelType.UINT8))
                .build();
    }

    private static void assertSuccess(ToolResult<StatsResult> result,
                                       java.util.function.Consumer<StatsResult> check) {
        if (result instanceof ToolResult.Success<StatsResult> s) {
            check.accept(s.value());
        } else {
            var f = (ToolResult.Failure<StatsResult>) result;
            fail("expected success, got " + f.kind() + ": " + f.message());
        }
    }

    private static void assertFailure(ToolResult<StatsResult> result,
                                       ErrorKind expectedKind,
                                       String messageContains) {
        if (result instanceof ToolResult.Failure<StatsResult> f) {
            assertEquals(expectedKind, f.kind());
            assertTrue(f.message().toLowerCase()
                            .contains(messageContains.toLowerCase()),
                    "expected message containing '" + messageContains
                    + "', got: " + f.message());
        } else {
            fail("expected " + expectedKind + " failure, got success");
        }
    }
}
