package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.FakeImageReader.FakeSeries;
import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;
import lab.kerrr.mcpbio.bioimageserver.PixelSize.LengthUnit;
import lab.kerrr.mcpbio.bioimageserver.ToolResult.ErrorKind;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class InspectImageToolTest {

    // ---- Success cases ----

    @Test
    void basicMetadataRetrieval() {
        var result = run("/image.tif", 0, DetailLevel.STANDARD);
        assertSuccess(result, meta -> {
            assertEquals("Fake Format", meta.formatName());
            assertEquals(1, meta.allSeries().size());
            assertEquals(64, meta.detailedSeries().sizeX());
            assertEquals(DetailLevel.STANDARD, meta.detailLevel());
        });
    }

    @Test
    void summaryDetailLevel() {
        var result = run("/image.tif", 0, DetailLevel.SUMMARY);
        assertSuccess(result, meta -> {
            assertEquals(DetailLevel.SUMMARY, meta.detailLevel());
            // Summary omits instrument
            assertNull(meta.detailedSeries().instrument());
        });
    }

    @Test
    void fullDetailLevel() {
        var result = runRich("/image.tif", 0, DetailLevel.FULL);
        assertSuccess(result, meta -> {
            assertEquals(DetailLevel.FULL, meta.detailLevel());
            assertFalse(meta.detailedSeries().extraMetadata().isEmpty());
            assertEquals(0, meta.omittedMetadataBytes());
        });
    }

    @Test
    void multiSeriesSelectsCorrectOne() {
        var factory = multiSeriesFactory();
        var request = new InspectImageTool.Request(
                "/image.lif", 1, DetailLevel.STANDARD,
                Duration.ofSeconds(5), 64 * 1024);
        var result = InspectImageTool.execute(
                request, PathValidator.allowAll(), factory);
        assertSuccess(result, meta -> {
            assertEquals(2, meta.allSeries().size());
            assertEquals(1, meta.detailedSeriesIndex());
            assertEquals(128, meta.detailedSeries().sizeX());
        });
    }

    @Test
    void allSeriesListedRegardlessOfSelection() {
        var factory = multiSeriesFactory();
        var request = new InspectImageTool.Request(
                "/image.lif", 0, DetailLevel.SUMMARY,
                Duration.ofSeconds(5), 64 * 1024);
        var result = InspectImageTool.execute(
                request, PathValidator.allowAll(), factory);
        assertSuccess(result, meta -> {
            assertEquals(2, meta.allSeries().size());
            assertEquals(64, meta.allSeries().get(0).sizeX());
            assertEquals(128, meta.allSeries().get(1).sizeX());
        });
    }

    @Test
    void defaultRequestUsesSeriesZeroAndStandard() {
        var request = InspectImageTool.Request.of("/image.tif");
        assertEquals(0, request.series());
        assertEquals(DetailLevel.STANDARD, request.detailLevel());
    }

    // ---- Access denied ----

    @Test
    void accessDeniedReturnsFailure() {
        PathValidator deny = path -> new AccessResult.Denied("not permitted");
        var request = InspectImageTool.Request.of("/secret/image.tif");
        var result = InspectImageTool.execute(
                request, deny, simpleFactory());
        assertFailure(result, ErrorKind.ACCESS_DENIED, "not permitted");
    }

    // ---- Invalid arguments ----

    @Test
    void seriesOutOfRangeReturnsFailure() {
        var result = run("/image.tif", 5, DetailLevel.STANDARD);
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "out of range");
    }

    @Test
    void negativeSeriesRejectedByRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> InspectImageTool.Request.of("/img.tif", -1,
                        null, null, null));
    }

    // ---- I/O errors ----

    @Test
    void ioErrorDuringOpenReturnsFailure() {
        Supplier<ImageReader> failingFactory = () -> new FailOnOpenReader();
        var request = InspectImageTool.Request.of("/image.tif");
        var result = InspectImageTool.execute(
                request, PathValidator.allowAll(), failingFactory);
        assertFailure(result, ErrorKind.IO_ERROR, "disk on fire");
    }

    // ---- Timeout ----

    @Test
    void timeoutReturnsFailure() {
        Supplier<ImageReader> slowFactory = () -> new SlowOpenReader();
        var request = new InspectImageTool.Request(
                "/image.tif", 0, DetailLevel.STANDARD,
                Duration.ofMillis(50), 64 * 1024);
        var result = InspectImageTool.execute(
                request, PathValidator.allowAll(), slowFactory);

        if (result instanceof ToolResult.Failure<ImageMetadata> f) {
            assertTrue(f.kind() == ErrorKind.TIMEOUT
                    || f.kind() == ErrorKind.IO_ERROR,
                    "should be TIMEOUT or IO_ERROR, got " + f.kind());
        } else {
            fail("expected failure, got success");
        }
    }

    // ---- Response size capping ----

    @Test
    void largeExtraMetadataGetsTruncated() {
        // Create a reader with huge extraMetadata
        var bigExtra = IntStream.range(0, 1000)
                .boxed()
                .collect(Collectors.toMap(
                        i -> "key_" + i,
                        i -> "x".repeat(100)));

        var series = new FakeSeries(
                "Big", 64, 64, 1, 1, 1,
                PixelType.UINT8, "XYCZT",
                null, null, null, List.of(), null, null, bigExtra);

        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(series).build();

        // Request FULL with a small cap
        var request = new InspectImageTool.Request(
                "/image.tif", 0, DetailLevel.FULL,
                Duration.ofSeconds(5), 2048);
        var result = InspectImageTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, meta -> {
            // Should have truncated or downgraded
            long estimated = InspectImageTool.estimateSize(meta);
            assertTrue(estimated <= 2048,
                    "response should be under budget, estimated " + estimated);
            assertTrue(meta.omittedMetadataBytes() > 0,
                    "should report omitted bytes");
        });
    }

    @Test
    void smallMetadataNotCapped() {
        var result = run("/image.tif", 0, DetailLevel.SUMMARY);
        assertSuccess(result, meta -> {
            long estimated = InspectImageTool.estimateSize(meta);
            assertTrue(estimated < 64 * 1024,
                    "simple metadata should be well under 64 KB");
        });
    }

    @Test
    void capResponseSizePreservesDimensionsEvenIfOverBudget() {
        // Even with a tiny budget, SUMMARY must be returned
        var metadata = simpleMetadata(DetailLevel.SUMMARY);
        var capped = InspectImageTool.capResponseSize(metadata, 1);
        assertEquals(64, capped.detailedSeries().sizeX());
        assertEquals(64, capped.detailedSeries().sizeY());
    }

    @Test
    void capResponseSizeDowngradesFullToStandardIfNeeded() {
        var richMeta = richMetadata(DetailLevel.FULL);
        // Use a budget that fits STANDARD but not FULL
        long fullSize = InspectImageTool.estimateSize(richMeta);
        var standardMeta = richMetadata(DetailLevel.STANDARD);
        long stdSize = InspectImageTool.estimateSize(standardMeta);
        long budget = (fullSize + stdSize) / 2;

        var capped = InspectImageTool.capResponseSize(richMeta, budget);
        assertTrue(capped.detailLevel() == DetailLevel.STANDARD
                || capped.detailLevel() == DetailLevel.FULL,
                "should be STANDARD or FULL (if truncation sufficed)");
        assertTrue(InspectImageTool.estimateSize(capped) <= budget,
                "capped response should fit budget");
    }

    // ---- Helpers ----

    private ToolResult<ImageMetadata> run(String path, int series,
                                          DetailLevel level) {
        var request = new InspectImageTool.Request(
                path, series, level, Duration.ofSeconds(5), 64 * 1024);
        return InspectImageTool.execute(
                request, PathValidator.allowAll(), simpleFactory());
    }

    private ToolResult<ImageMetadata> runRich(String path, int series,
                                              DetailLevel level) {
        var request = new InspectImageTool.Request(
                path, series, level, Duration.ofSeconds(5), 64 * 1024);
        return InspectImageTool.execute(
                request, PathValidator.allowAll(), richFactory());
    }

    private static Supplier<ImageReader> simpleFactory() {
        return () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(64, 64, 1, 1, 1, PixelType.UINT8))
                .build();
    }

    private static Supplier<ImageReader> richFactory() {
        return () -> FakeImageReader.builder()
                .formatName("Zeiss CZI")
                .addSeries(richSeries())
                .build();
    }

    private static Supplier<ImageReader> multiSeriesFactory() {
        return () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(64, 64, 1, 1, 1, PixelType.UINT8))
                .addSeries(FakeSeries.simple(128, 128, 10, 3, 1, PixelType.UINT16))
                .build();
    }

    private static FakeSeries richSeries() {
        var channels = List.of(
                ChannelInfo.of(0, "DAPI", "DAPI", 405.0, 461.0, 0xFF0000FF),
                ChannelInfo.of(1, "GFP", "EGFP", 488.0, 509.0, 0xFF00FF00));
        var instrument = new InstrumentInfo(
                "Plan-Apo 63x", "Zeiss", 63.0, null, 1.4, "Oil", "PlanApo");
        var extra = Map.of("LaserPower", "100", "PinholeSize", "1.0 AU");
        return new FakeSeries(
                "Stack", 512, 512, 10, 2, 1,
                PixelType.UINT16, "XYCZT",
                PixelSize.of(0.103, LengthUnit.MICROMETER),
                PixelSize.of(0.103, LengthUnit.MICROMETER),
                PixelSize.of(0.5, LengthUnit.MICROMETER),
                channels, instrument, "2024-01-15T10:30:00Z", extra);
    }

    private static ImageMetadata simpleMetadata(DetailLevel level) {
        try (var reader = FakeImageReader.builder()
                .addSeries(FakeSeries.simple(64, 64, 1, 1, 1, PixelType.UINT8))
                .build()) {
            reader.open(Path.of("/fake"));
            return reader.getMetadata(0, level);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static ImageMetadata richMetadata(DetailLevel level) {
        try (var reader = FakeImageReader.builder()
                .formatName("Zeiss CZI")
                .addSeries(richSeries())
                .build()) {
            reader.open(Path.of("/fake"));
            return reader.getMetadata(0, level);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> void assertSuccess(ToolResult<T> result,
                                           java.util.function.Consumer<T> check) {
        if (result instanceof ToolResult.Success<T> s) {
            check.accept(s.value());
        } else {
            var f = (ToolResult.Failure<T>) result;
            fail("expected success, got " + f.kind() + ": " + f.message());
        }
    }

    private static <T> void assertFailure(ToolResult<T> result,
                                           ErrorKind expectedKind,
                                           String messageContains) {
        if (result instanceof ToolResult.Failure<T> f) {
            assertEquals(expectedKind, f.kind());
            assertTrue(f.message().contains(messageContains),
                    "expected message containing '" + messageContains
                    + "', got: " + f.message());
        } else {
            fail("expected " + expectedKind + " failure, got success");
        }
    }

    // ---- Fake readers for error testing ----

    /** Reader whose open() always throws IOException. */
    private static class FailOnOpenReader implements ImageReader {
        @Override public void open(Path path) throws IOException {
            throw new IOException("disk on fire");
        }
        @Override public int getSeriesCount() { return 0; }
        @Override public ImageMetadata getMetadata(int s, DetailLevel d) {
            return null;
        }
        @Override public boolean isLittleEndian(int s) { return true; }
        @Override public byte[] readPlane(int s, int c, int z, int t) {
            return new byte[0];
        }
        @Override public void close() {}
    }

    /** Reader whose open() blocks until interrupted. */
    private static class SlowOpenReader implements ImageReader {
        @Override public void open(Path path) throws IOException {
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException e) {
                throw new IOException("interrupted during open", e);
            }
        }
        @Override public int getSeriesCount() { return 0; }
        @Override public ImageMetadata getMetadata(int s, DetailLevel d) {
            return null;
        }
        @Override public boolean isLittleEndian(int s) { return true; }
        @Override public byte[] readPlane(int s, int c, int z, int t) {
            return new byte[0];
        }
        @Override public void close() {}
    }
}
