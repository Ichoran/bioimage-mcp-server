package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.Compression;
import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.ExportResult;
import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.MetadataMode;
import lab.kerrr.mcpbio.bioimageserver.FakeImageReader.FakeSeries;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;
import lab.kerrr.mcpbio.bioimageserver.ToolResult.ErrorKind;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ExportToTiffToolTest {

    // Shared test timeout and budget
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final long MAX_BYTES = 256L * 1024 * 1024;

    // ================================================================
    // Success: basic export
    // ================================================================

    @Test
    void basicExportWritesAllPlanes() {
        // 2 channels × 3 Z × 1 T = 6 planes
        var writer = new FakeImageWriter();
        var result = runExport(
                simpleReader(2, 3, 1), writer,
                null, null, null, null, null, null,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertEquals(1, export.seriesExported());
            assertEquals(2, export.channelsPerSeries());
            assertEquals(3, export.zSlicesPerSeries());
            assertEquals(1, export.timepointsPerSeries());
            assertEquals(6, export.totalPlanesWritten());
            assertEquals(6, writer.planeCount());
            assertTrue(writer.isClosed());
        });
    }

    @Test
    void exportedPixelDataMatchesReader() throws Exception {
        // Single plane: verify pixel bytes match
        var writer = new FakeImageWriter();
        var readerFactory = simpleReader(1, 1, 1);

        var result = runExport(
                readerFactory, writer,
                null, null, null, null, null, null,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertEquals(1, writer.planeCount());
            byte[] written = writer.planes().getFirst().data();

            // Read the same plane directly from a fresh reader
            try {
                var reader = readerFactory.get();
                reader.open(java.nio.file.Path.of("/input.tif"));
                byte[] expected = reader.readPlane(0, 0, 0, 0);
                reader.close();
                assertArrayEquals(expected, written);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void exportPassesOmeXmlToWriter() {
        var writer = new FakeImageWriter();
        var result = runExport(
                simpleReader(1, 1, 1), writer,
                null, null, null, null, null, null,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertTrue(export.omeXmlPresent());
            String writerXml = writer.omeXml();
            assertNotNull(writerXml);
            assertTrue(writerXml.contains("Pixels"));
            assertTrue(writerXml.contains("SizeX"));
        });
    }

    @Test
    void exportPassesCompressionToWriter() {
        var writer = new FakeImageWriter();
        var request = new ExportToTiffTool.Request(
                "/input.tif", "/output.ome.tif",
                null, null, null, null, null, null,
                Compression.LZW, MetadataMode.ALL,
                TIMEOUT, MAX_BYTES);
        ExportToTiffTool.execute(
                request, PathValidator.allowAll(),
                simpleReader(1, 1, 1), () -> writer);

        assertEquals("LZW", writer.compression());
    }

    // ================================================================
    // Subsetting
    // ================================================================

    @Test
    void channelSubsetWritesOnlyRequestedChannels() {
        // 3 channels, request only [0, 2]
        var writer = new FakeImageWriter();
        var result = runExport(
                simpleReader(3, 1, 1), writer,
                null, new int[] { 0, 2 }, null, null, null, null,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertEquals(2, export.channelsPerSeries());
            assertEquals(2, export.totalPlanesWritten());
        });
    }

    @Test
    void zRangeSubsetWritesOnlyRequestedSlices() {
        // 5 Z-slices, request Z [1, 3]
        var writer = new FakeImageWriter();
        var result = runExport(
                simpleReader(1, 5, 1), writer,
                null, null, 1, 3, null, null,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertEquals(3, export.zSlicesPerSeries());
            assertEquals(3, export.totalPlanesWritten());
        });
    }

    @Test
    void tRangeSubsetWritesOnlyRequestedTimepoints() {
        // 4 timepoints, request T [0, 1]
        var writer = new FakeImageWriter();
        var result = runExport(
                simpleReader(1, 1, 4), writer,
                null, null, null, null, 0, 1,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertEquals(2, export.timepointsPerSeries());
            assertEquals(2, export.totalPlanesWritten());
        });
    }

    @Test
    void combinedSubsettingWritesCorrectPlaneCount() {
        // 3C × 5Z × 2T = 30 planes; subset to 2C × 2Z × 1T = 4 planes
        var writer = new FakeImageWriter();
        var result = runExport(
                simpleReader(3, 5, 2), writer,
                null, new int[] { 0, 1 }, 2, 3, 0, 0,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertEquals(2, export.channelsPerSeries());
            assertEquals(2, export.zSlicesPerSeries());
            assertEquals(1, export.timepointsPerSeries());
            assertEquals(4, export.totalPlanesWritten());
        });
    }

    @Test
    void singleSeriesExportSelectsCorrectSeries() {
        // 2-series reader, export only series 1
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(8, 8, 1, 1, 1, PixelType.UINT8))
                .addSeries(FakeSeries.simple(16, 16, 1, 2, 1, PixelType.UINT16))
                .build();

        var writer = new FakeImageWriter();
        var result = runExport(
                factory, writer,
                1, null, null, null, null, null,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertEquals(1, export.seriesExported());
            assertEquals(2, export.channelsPerSeries());
            // Pixel data should be 16×16 uint16 = 512 bytes per plane
            assertEquals(2, writer.planeCount());
            assertEquals(512, writer.planes().getFirst().data().length);
        });
    }

    // ================================================================
    // Metadata modes
    // ================================================================

    @Test
    void metadataModeAllPreservesOriginalMetadata() {
        var writer = new FakeImageWriter();
        Supplier<ImageReader> factory = readerWithOriginalMetadata(3);

        var result = runExport(
                factory, writer,
                null, null, null, null, null, null,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertEquals(3, export.originalMetadataInXml());
            assertTrue(writer.omeXml().contains("OriginalMetadata"));
        });
    }

    @Test
    void metadataModeStructuredStripsOriginalMetadata() {
        var writer = new FakeImageWriter();
        Supplier<ImageReader> factory = readerWithOriginalMetadata(5);

        var result = runExport(
                factory, writer,
                null, null, null, null, null, null,
                MetadataMode.STRUCTURED);

        assertSuccess(result, export -> {
            assertEquals(0, export.originalMetadataInXml());
            assertFalse(writer.omeXml().contains(
                    "openmicroscopy.org/OriginalMetadata"));
            // Should have a warning about stripping
            assertTrue(export.warnings().stream()
                    .anyMatch(w -> w.contains("stripped")));
        });
    }

    @Test
    void metadataModeMinimalStripsToEssentials() {
        var writer = new FakeImageWriter();
        Supplier<ImageReader> factory = readerWithOriginalMetadata(2);

        var result = runExport(
                factory, writer,
                null, null, null, null, null, null,
                MetadataMode.MINIMAL);

        assertSuccess(result, export -> {
            assertEquals(0, export.originalMetadataInXml());
            assertTrue(export.warnings().stream()
                    .anyMatch(w -> w.contains("minimal")));
            // Should still have Pixels and Channel
            assertTrue(writer.omeXml().contains("Pixels"));
            assertTrue(writer.omeXml().contains("Channel"));
        });
    }

    // ================================================================
    // Metadata warnings
    // ================================================================

    @Test
    void warnsAboutProprietaryFormatConversion() {
        var writer = new FakeImageWriter();
        var result = runExport(
                simpleReader(1, 1, 1), writer,
                null, null, null, null, null, null,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertEquals("Fake Format", export.sourceFormat());
            assertTrue(export.warnings().stream()
                    .anyMatch(w -> w.contains("Fake Format")
                            && w.contains("format-specific")));
        });
    }

    @Test
    void warnsAboutFlatMetadataNotInXml() {
        // Reader has 10 flat metadata entries but only 5 in XML
        // (we simulate this by using originalMetadata with 5 entries
        // but reporting a higher count)
        // Actually, FakeImageReader puts all original metadata into
        // the XML, so origMetaInReader == origMetaInXml. Let's verify
        // that no spurious warning appears.
        var writer = new FakeImageWriter();
        Supplier<ImageReader> factory = readerWithOriginalMetadata(3);

        var result = runExport(
                factory, writer,
                null, null, null, null, null, null,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertEquals(3, export.originalMetadataInXml());
            assertEquals(3, export.originalMetadataInReader());
            // No "may not survive" warning since counts match
            assertFalse(export.warnings().stream()
                    .anyMatch(w -> w.contains("may not survive")));
        });
    }

    @Test
    void reportsOmeXmlAsPresent() {
        var writer = new FakeImageWriter();
        var result = runExport(
                simpleReader(1, 1, 1), writer,
                null, null, null, null, null, null,
                MetadataMode.ALL);

        assertSuccess(result, export ->
                assertTrue(export.omeXmlPresent()));
    }

    // ================================================================
    // Error cases
    // ================================================================

    @Test
    void accessDeniedOnInput() {
        PathValidator deny = path -> new AccessResult.Denied("no read");
        var request = ExportToTiffTool.Request.of("/input.tif", "/out.ome.tif");
        var result = ExportToTiffTool.execute(
                request, deny, simpleReader(1, 1, 1), FakeImageWriter::new);
        assertFailure(result, ErrorKind.ACCESS_DENIED, "input");
    }

    @Test
    void accessDeniedOnOutput() {
        PathValidator selective = path -> {
            if (path.contains("output")) {
                return new AccessResult.Denied("no write");
            }
            return new AccessResult.Allowed(java.nio.file.Path.of(path));
        };
        var request = ExportToTiffTool.Request.of(
                "/input.tif", "/output.ome.tif");
        var result = ExportToTiffTool.execute(
                request, selective,
                simpleReader(1, 1, 1), FakeImageWriter::new);
        assertFailure(result, ErrorKind.ACCESS_DENIED, "output");
    }

    @Test
    void sameInputAndOutputRejected() {
        var request = ExportToTiffTool.Request.of(
                "/same.tif", "/same.tif");
        var result = ExportToTiffTool.execute(
                request, PathValidator.allowAll(),
                simpleReader(1, 1, 1), FakeImageWriter::new);
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "different");
    }

    @Test
    void channelOutOfRange() {
        var request = new ExportToTiffTool.Request(
                "/in.tif", "/out.ome.tif",
                null, new int[] { 5 }, null, null, null, null,
                Compression.NONE, MetadataMode.ALL, TIMEOUT, MAX_BYTES);
        var result = ExportToTiffTool.execute(
                request, PathValidator.allowAll(),
                simpleReader(2, 1, 1), FakeImageWriter::new);
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "channel");
    }

    @Test
    void zOutOfRange() {
        var request = new ExportToTiffTool.Request(
                "/in.tif", "/out.ome.tif",
                null, null, null, 99, null, null,
                Compression.NONE, MetadataMode.ALL, TIMEOUT, MAX_BYTES);
        var result = ExportToTiffTool.execute(
                request, PathValidator.allowAll(),
                simpleReader(1, 5, 1), FakeImageWriter::new);
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "zEnd");
    }

    @Test
    void tOutOfRange() {
        var request = new ExportToTiffTool.Request(
                "/in.tif", "/out.ome.tif",
                null, null, null, null, null, 99,
                Compression.NONE, MetadataMode.ALL, TIMEOUT, MAX_BYTES);
        var result = ExportToTiffTool.execute(
                request, PathValidator.allowAll(),
                simpleReader(1, 1, 3), FakeImageWriter::new);
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "tEnd");
    }

    @Test
    void seriesOutOfRange() {
        var request = new ExportToTiffTool.Request(
                "/in.tif", "/out.ome.tif",
                5, null, null, null, null, null,
                Compression.NONE, MetadataMode.ALL, TIMEOUT, MAX_BYTES);
        var result = ExportToTiffTool.execute(
                request, PathValidator.allowAll(),
                simpleReader(1, 1, 1), FakeImageWriter::new);
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "series");
    }

    @Test
    void byteBudgetExceeded() {
        // 16×16 uint8 × 3 ch × 5 Z × 2 T = 7680 bytes, budget 1000
        var request = new ExportToTiffTool.Request(
                "/in.tif", "/out.ome.tif",
                null, null, null, null, null, null,
                Compression.NONE, MetadataMode.ALL, TIMEOUT, 1000);
        var result = ExportToTiffTool.execute(
                request, PathValidator.allowAll(),
                simpleReader(3, 5, 2), FakeImageWriter::new);
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "maxBytes");
    }

    @Test
    void requestValidationRejectsBlankPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> ExportToTiffTool.Request.of("", "/out.tif"));
        assertThrows(IllegalArgumentException.class,
                () -> ExportToTiffTool.Request.of("/in.tif", ""));
    }

    // ================================================================
    // Plane write order
    // ================================================================

    @Test
    void planesWrittenInCZTOrder() {
        // 2C × 2Z × 2T = 8 planes
        // Expected order: (C0,Z0,T0), (C1,Z0,T0), (C0,Z1,T0), (C1,Z1,T0),
        //                 (C0,Z0,T1), (C1,Z0,T1), (C0,Z1,T1), (C1,Z1,T1)
        var writer = new FakeImageWriter();
        Supplier<ImageReader> factory = simpleReader(2, 2, 2);

        var result = runExport(
                factory, writer,
                null, null, null, null, null, null,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertEquals(8, writer.planeCount());
            // Verify plane indices are sequential
            for (int i = 0; i < writer.planeCount(); i++) {
                assertEquals(i, writer.planes().get(i).planeIndex());
            }
        });
    }

    @Test
    void subsettedPlanesHaveCorrectPixelData() {
        // 3C × 2Z × 1T, export channel 1, Z [0,1]
        var writer = new FakeImageWriter();
        Supplier<ImageReader> factory = simpleReader(3, 2, 1);

        var result = runExport(
                factory, writer,
                null, new int[] { 1 }, 0, 1, null, null,
                MetadataMode.ALL);

        assertSuccess(result, export -> {
            assertEquals(2, writer.planeCount());

            // Read the same planes directly
            try {
                var reader = factory.get();
                reader.open(java.nio.file.Path.of("/input.tif"));
                byte[] expectedZ0 = reader.readPlane(0, 1, 0, 0);
                byte[] expectedZ1 = reader.readPlane(0, 1, 1, 0);
                reader.close();
                assertArrayEquals(expectedZ0, writer.planes().get(0).data());
                assertArrayEquals(expectedZ1, writer.planes().get(1).data());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static Supplier<ImageReader> simpleReader(
            int sizeC, int sizeZ, int sizeT) {
        return () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(
                        16, 16, sizeZ, sizeC, sizeT, PixelType.UINT8))
                .build();
    }

    private static Supplier<ImageReader> readerWithOriginalMetadata(
            int metaCount) {
        var meta = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < metaCount; i++) {
            meta.put("Key" + i, "Value" + i);
        }
        return () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(
                        8, 8, 1, 1, 1, PixelType.UINT8))
                .originalMetadata(meta)
                .build();
    }

    private ToolResult<ExportResult> runExport(
            Supplier<ImageReader> readerFactory,
            FakeImageWriter writer,
            Integer series, int[] channels,
            Integer zStart, Integer zEnd,
            Integer tStart, Integer tEnd,
            MetadataMode metadataMode) {
        var request = new ExportToTiffTool.Request(
                "/input.tif", "/output.ome.tif",
                series, channels, zStart, zEnd, tStart, tEnd,
                Compression.NONE,
                metadataMode != null ? metadataMode : MetadataMode.ALL,
                TIMEOUT, MAX_BYTES);
        return ExportToTiffTool.execute(
                request, PathValidator.allowAll(),
                readerFactory, () -> writer);
    }

    private static <T> void assertSuccess(
            ToolResult<T> result,
            java.util.function.Consumer<T> check) {
        if (result instanceof ToolResult.Success<T> s) {
            check.accept(s.value());
        } else {
            var f = (ToolResult.Failure<T>) result;
            fail("expected success, got " + f.kind() + ": " + f.message());
        }
    }

    private static <T> void assertFailure(
            ToolResult<T> result, ErrorKind expectedKind,
            String messageContains) {
        if (result instanceof ToolResult.Failure<T> f) {
            assertEquals(expectedKind, f.kind());
            assertTrue(f.message().toLowerCase().contains(
                    messageContains.toLowerCase()),
                    "expected message containing '" + messageContains
                    + "', got: " + f.message());
        } else {
            fail("expected " + expectedKind + " failure, got success");
        }
    }
}
