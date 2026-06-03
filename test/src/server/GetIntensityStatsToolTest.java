package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.FakeImageReader.FakeSeries;
import lab.kerrr.mcpbio.bioimageserver.GetIntensityStatsTool.StatsResult;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;
import lab.kerrr.mcpbio.bioimageserver.ToolResult.ErrorKind;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class GetIntensityStatsToolTest {

    private static final long BIG = 512L * 1024 * 1024;

    /** Build a stats Request with slice selections. */
    private static GetIntensityStatsTool.Request req(
            String path, Slice channels, Slice z, Slice t, long maxBytes) {
        return new GetIntensityStatsTool.Request(
                path, 0, channels, z, t, 256,
                Duration.ofSeconds(5), maxBytes);
    }

    private static int last(int[] a) { return a[a.length - 1]; }

    // ================================================================
    // Basic success cases
    // ================================================================

    @Test
    void singleChannelReturnsOneStats() {
        var result = run("/image.tif", Slice.all(), Slice.all(), Slice.all());
        assertSuccess(result, sr -> {
            assertEquals(1, sr.perChannel().size());
            assertEquals(0, sr.perChannel().get(0).channel());
        });
    }

    @Test
    void multiChannelReturnsStatsPerChannel() {
        Supplier<ImageReader> factory = factory(16, 16, 1, 3, 1, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.all(), Slice.all(), BIG),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(3, sr.perChannel().size());
            assertEquals(0, sr.perChannel().get(0).channel());
            assertEquals(2, sr.perChannel().get(2).channel());
            assertEquals(0, sr.channels()[0]);
            assertEquals(2, last(sr.channels()));
        });
    }

    @Test
    void specificChannelReturnsOnlyThatChannel() {
        Supplier<ImageReader> factory = factory(16, 16, 1, 3, 1, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.single(2), Slice.all(), Slice.all(), BIG),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(1, sr.perChannel().size());
            assertEquals(2, sr.perChannel().get(0).channel());
            assertEquals(2, sr.channels()[0]);
            assertEquals(2, last(sr.channels()));
        });
    }

    // ================================================================
    // Stats are correct for known synthetic data
    // ================================================================

    @Test
    void uint8StatsMatchExpected() {
        var result = run("/image.tif", Slice.all(), Slice.all(), Slice.all());
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
        Supplier<ImageReader> factory = factory(16, 16, 1, 3, 1, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.all(), Slice.all(), BIG),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr ->
                assertNotEquals(sr.perChannel().get(0).mean(),
                        sr.perChannel().get(1).mean()));
    }

    // ================================================================
    // Multi-Z accumulation
    // ================================================================

    @Test
    void allZSlicesAccumulated() {
        Supplier<ImageReader> factory = factory(4, 4, 4, 1, 1, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.all(), Slice.all(), BIG),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(64, histTotal(sr));
            assertEquals(0, sr.zRequested()[0]);
            assertEquals(3, last(sr.zRequested()));
        });
    }

    @Test
    void specificZSliceReadsOnlyThatSlice() {
        Supplier<ImageReader> factory = factory(4, 4, 4, 1, 1, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.single(2), Slice.all(), BIG),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(16, histTotal(sr));
            assertEquals(2, sr.zRequested()[0]);
            assertEquals(2, last(sr.zRequested()));
        });
    }

    // ================================================================
    // Timepoint handling
    // ================================================================

    @Test
    void adaptiveReadsAllTimepointsWhenSmall() {
        Supplier<ImageReader> factory = factory(4, 4, 1, 1, 5, PixelType.UINT8);
        var request = GetIntensityStatsTool.Request.of("/image.tif");
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(80, histTotal(sr));
            assertEquals(0, sr.tRequested()[0]);
            assertEquals(4, last(sr.tRequested()));
            assertEquals(5, sr.timepointsUsed().length);
        });
    }

    @Test
    void specificTimepointReadsOnlyThat() {
        Supplier<ImageReader> factory = factory(4, 4, 1, 1, 5, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.all(), Slice.single(3), BIG),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(16, histTotal(sr));
            assertEquals(3, sr.tRequested()[0]);
            assertEquals(3, last(sr.tRequested()));
        });
    }

    // ================================================================
    // Slice selections (ranges and lists)
    // ================================================================

    @Test
    void channelRangeSelectsSubset() {
        Supplier<ImageReader> factory = factory(8, 8, 1, 4, 1, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.parse("1:3", "c"), Slice.all(),
                        Slice.all(), BIG),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(2, sr.perChannel().size());
            assertEquals(1, sr.perChannel().get(0).channel());
            assertEquals(2, sr.perChannel().get(1).channel());
            assertEquals(1, sr.channels()[0]);
            assertEquals(2, last(sr.channels()));
        });
    }

    @Test
    void channelListSelectsNonContiguous() {
        Supplier<ImageReader> factory = factory(8, 8, 1, 5, 1, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.parse("0,2,4", "c"), Slice.all(),
                        Slice.all(), BIG),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(3, sr.perChannel().size());
            assertArrayEquals(new int[] {0, 2, 4}, sr.channels());
            assertEquals(0, sr.perChannel().get(0).channel());
            assertEquals(2, sr.perChannel().get(1).channel());
            assertEquals(4, sr.perChannel().get(2).channel());
        });
    }

    @Test
    void zRangeSelectsSubset() {
        Supplier<ImageReader> factory = factory(4, 4, 8, 1, 1, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.parse("2:6", "z"),
                        Slice.all(), BIG),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(64, histTotal(sr));
            assertEquals(2, sr.zRequested()[0]);
            assertEquals(5, last(sr.zRequested()));
            assertEquals(4, sr.zSlicesUsed().length);
        });
    }

    @Test
    void tRangeSelectsSubset() {
        Supplier<ImageReader> factory = factory(4, 4, 1, 1, 10, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.all(),
                        Slice.parse("1:4", "t"), BIG),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(48, histTotal(sr));
            assertEquals(1, sr.tRequested()[0]);
            assertEquals(3, last(sr.tRequested()));
            assertEquals(3, sr.timepointsUsed().length);
        });
    }

    @Test
    void adaptiveStopsByByteBudget() {
        Supplier<ImageReader> factory = factory(32, 32, 10, 1, 1, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.all(), Slice.all(), 3072),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertTrue(sr.perChannel().get(0).sampled());
            assertEquals(3, sr.zSlicesUsed().length);
            assertEquals(0, sr.zRequested()[0]);
            assertEquals(9, last(sr.zRequested()));
        });
    }

    @Test
    void adaptiveVolumeReadsFullZBeforeStopping() {
        Supplier<ImageReader> factory = factory(4, 4, 4, 1, 5, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.all(), Slice.all(), 100),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(4, sr.zSlicesUsed().length);
            assertEquals(1, sr.timepointsUsed().length);
            assertTrue(sr.perChannel().get(0).sampled());
        });
    }

    @Test
    void adaptiveVolumeMultiChannel() {
        Supplier<ImageReader> factory = factory(4, 4, 3, 2, 4, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.all(), Slice.all(), 250),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertEquals(2, sr.perChannel().size());
            assertEquals(3, sr.zSlicesUsed().length);
            assertEquals(2, sr.timepointsUsed().length);
            assertTrue(sr.perChannel().get(0).sampled());
        });
    }

    @Test
    void resultIncludesPixelType() {
        var result = run("/image.tif", Slice.all(), Slice.all(), Slice.all());
        assertSuccess(result, sr -> assertEquals(PixelType.UINT8, sr.pixelType()));
    }

    // ================================================================
    // Byte budget and subsampling
    // ================================================================

    @Test
    void subsamplesWhenOverBudget() {
        Supplier<ImageReader> factory = factory(32, 32, 10, 1, 1, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.all(), Slice.all(), 3072),
                PathValidator.allowAll(), factory);

        assertSuccess(result, sr -> {
            assertTrue(sr.perChannel().get(0).sampled());
            assertTrue(sr.perChannel().get(0).sampledFraction() < 1.0);
            assertTrue(sr.perChannel().get(0).sampledFraction() > 0.0);
            assertTrue(sr.zSlicesUsed().length < 10);
        });
    }

    @Test
    void budgetTooSmallForOnePerChannelFails() {
        Supplier<ImageReader> factory = factory(32, 32, 1, 2, 1, PixelType.UINT8);
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.all(), Slice.all(), 500),
                PathValidator.allowAll(), factory);
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "budget");
    }

    // ================================================================
    // Histogram properties
    // ================================================================

    @Test
    void histogramBinsConfigurable() {
        var request = new GetIntensityStatsTool.Request(
                "/image.tif", 0, Slice.all(), Slice.all(), Slice.all(), 50,
                Duration.ofSeconds(5), BIG);
        var result = GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), simpleFactory());

        assertSuccess(result, sr -> {
            assertEquals(50, sr.perChannel().get(0).histogramCounts().length);
            assertEquals(51, sr.perChannel().get(0).histogramBinEdges().length);
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
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.single(5), Slice.all(), Slice.all(), BIG),
                PathValidator.allowAll(), simpleFactory());
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "out of range");
    }

    @Test
    void zOutOfRange() {
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.single(99), Slice.all(), BIG),
                PathValidator.allowAll(), simpleFactory());
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "out of range");
    }

    @Test
    void timepointOutOfRange() {
        var result = GetIntensityStatsTool.execute(
                req("/image.tif", Slice.all(), Slice.all(), Slice.single(99), BIG),
                PathValidator.allowAll(), simpleFactory());
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "out of range");
    }

    @Test
    void missingSelectionIsRejected() {
        // The wire layer requires explicit selections; the record enforces it.
        assertThrows(IllegalArgumentException.class, () ->
                new GetIntensityStatsTool.Request("/image.tif", 0,
                        null, Slice.all(), Slice.all(), 256,
                        Duration.ofSeconds(5), BIG));
    }

    // ================================================================
    // Subsampling helpers
    // ================================================================

    @Test
    void evenlySpacedSelectsEndpoints() {
        int[] source = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
        int[] result = GetIntensityStatsTool.evenlySpaced(source, 3);
        assertEquals(3, result.length);
        assertEquals(0, result[0]);
        assertEquals(5, result[1]);
        assertEquals(9, result[2]);
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
        Supplier<ImageReader> factory = factory(8, 8, 1, 1, 1, PixelType.UINT16);
        var result = GetIntensityStatsTool.execute(
                GetIntensityStatsTool.Request.of("/image.tif"),
                PathValidator.allowAll(), factory);

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
        Supplier<ImageReader> factory = factory(8, 8, 1, 1, 1, PixelType.INT32);
        var result = GetIntensityStatsTool.execute(
                GetIntensityStatsTool.Request.of("/image.tif"),
                PathValidator.allowAll(), factory);

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
            String path, Slice channels, Slice z, Slice t) {
        var request = GetIntensityStatsTool.Request.of(
                path, null, channels, z, t, null, Duration.ofSeconds(5), null);
        return GetIntensityStatsTool.execute(
                request, PathValidator.allowAll(), simpleFactory());
    }

    private static Supplier<ImageReader> factory(
            int x, int y, int z, int c, int t, PixelType pt) {
        return () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(x, y, z, c, t, pt))
                .build();
    }

    private static Supplier<ImageReader> simpleFactory() {
        return factory(4, 4, 1, 1, 1, PixelType.UINT8);
    }

    private static long histTotal(StatsResult sr) {
        long total = 0;
        for (long c : sr.perChannel().get(0).histogramCounts()) total += c;
        return total;
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
