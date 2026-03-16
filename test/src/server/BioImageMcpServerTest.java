package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.Compression;
import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.MetadataMode;
import lab.kerrr.mcpbio.bioimageserver.GetThumbnailTool.Projection;
import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.FakeImageReader.FakeSeries;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class BioImageMcpServerTest {

    // ================================================================
    // Builder tests
    // ================================================================

    @Test
    void canInstantiateServerViaBuilder() {
        BioImageMcpServer server = BioImageMcpServer.builder().build();
        assertNotNull(server);
    }

    @Test
    void builderAcceptsAllowAndDenyPaths() {
        BioImageMcpServer server = BioImageMcpServer.builder()
                .allow("/tmp/data")
                .deny("/tmp/data/private")
                .build();
        assertNotNull(server);
    }

    // ================================================================
    // Argument parsing tests (via reflection-free approach:
    // exercise the same tool Request factories with parsed args)
    // ================================================================

    @Test
    void inspectImageDefaultsApplied() {
        var req = InspectImageTool.Request.of(
                "/tmp/test.czi", null, null, null, null);
        assertEquals(0, req.series());
        assertEquals(DetailLevel.STANDARD, req.detailLevel());
        assertEquals(Duration.ofSeconds(30), req.timeout());
        assertEquals(64 * 1024, req.maxResponseBytes());
    }

    @Test
    void getThumbnailDefaultsApplied() {
        var req = GetThumbnailTool.Request.of(
                "/tmp/test.czi", null, null, null, null, null, null, null);
        assertEquals(0, req.series());
        assertEquals(Projection.ADAPTIVE, req.projection());
        assertNull(req.channels());
        assertEquals(0, req.timepoint());
        assertEquals(1024, req.maxSize());
    }

    @Test
    void getPlaneDefaultsApplied() {
        var req = GetPlaneTool.Request.of(
                "/tmp/test.czi", null, null, null, null, null, null, null, null);
        assertEquals(0, req.series());
        assertEquals(0, req.channel());
        assertEquals(0, req.z());
        assertEquals(0, req.timepoint());
        assertTrue(req.normalize());
        assertNull(req.maxSize());
    }

    @Test
    void getIntensityStatsDefaultsApplied() {
        var req = GetIntensityStatsTool.Request.of(
                "/tmp/test.czi", null, null, null, null, null, null, null);
        assertEquals(0, req.series());
        assertNull(req.channels());
        assertNull(req.zRange());
        assertNull(req.tRange());
        assertEquals(256, req.histogramBins());
    }

    @Test
    void exportToTiffDefaultsApplied() {
        var req = ExportToTiffTool.Request.of(
                "/tmp/in.czi", "/tmp/out.ome.tiff",
                null, null, null, null, null, null,
                null, null, null, null);
        assertNull(req.series());
        assertNull(req.channels());
        assertEquals(Compression.NONE, req.compression());
        assertEquals(MetadataMode.ALL, req.metadataMode());
    }

    // ================================================================
    // End-to-end tool execution with FakeImageReader
    // (Tests the same code path as the MCP handlers, just calling
    // the tool execute methods directly with parsed arguments.)
    // ================================================================

    private static Supplier<ImageReader> fakeReader() {
        return () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(64, 64, 5, 2, 3, PixelType.UINT16))
                .build();
    }

    @Test
    void inspectImageEndToEnd(@TempDir Path tempDir) throws IOException {
        // Create a dummy file so path validation can resolve it
        var file = Files.createFile(tempDir.resolve("test.tif"));

        var req = InspectImageTool.Request.of(file.toString());
        var result = InspectImageTool.execute(
                req, PathValidator.allowAll(), fakeReader());

        assertInstanceOf(ToolResult.Success.class, result);
        var metadata = ((ToolResult.Success<ImageMetadata>) result).value();

        // Verify serialization produces valid JSON
        String json = JsonUtil.toJson(JsonUtil.toMap(metadata));
        assertTrue(json.contains("\"sizeX\" : 64"));
        assertTrue(json.contains("\"sizeC\" : 2"));
        assertTrue(json.contains("\"sizeZ\" : 5"));
    }

    @Test
    void getPlaneEndToEnd(@TempDir Path tempDir) throws IOException {
        var file = Files.createFile(tempDir.resolve("test.tif"));

        var req = GetPlaneTool.Request.of(file.toString(), 0);
        var result = GetPlaneTool.execute(
                req, PathValidator.allowAll(), fakeReader());

        assertInstanceOf(ToolResult.Success.class, result);
        byte[] png = ((ToolResult.Success<byte[]>) result).value();
        // PNG magic bytes
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
    }

    @Test
    void getThumbnailEndToEnd(@TempDir Path tempDir) throws IOException {
        var file = Files.createFile(tempDir.resolve("test.tif"));

        var req = GetThumbnailTool.Request.of(file.toString());
        var result = GetThumbnailTool.execute(
                req, PathValidator.allowAll(), fakeReader());

        assertInstanceOf(ToolResult.Success.class, result);
        var tr = ((ToolResult.Success<GetThumbnailTool.ThumbnailResult>) result).value();
        // Should be a concrete projection, not ADAPTIVE
        assertNotEquals(Projection.ADAPTIVE, tr.projectionUsed());

        // Serialization should work
        String json = JsonUtil.toJson(JsonUtil.toMap(tr));
        assertTrue(json.contains("projection_used"));
    }

    @Test
    void getIntensityStatsEndToEnd(@TempDir Path tempDir) throws IOException {
        var file = Files.createFile(tempDir.resolve("test.tif"));

        // Single channel, single z
        var req = GetIntensityStatsTool.Request.of(
                file.toString(), null,
                GetIntensityStatsTool.Range.of(0),
                GetIntensityStatsTool.Range.of(0),
                GetIntensityStatsTool.Range.of(0),
                null, null, null);
        var result = GetIntensityStatsTool.execute(
                req, PathValidator.allowAll(), fakeReader());

        assertInstanceOf(ToolResult.Success.class, result);
        var stats = ((ToolResult.Success<GetIntensityStatsTool.StatsResult>) result).value();

        assertEquals(1, stats.perChannel().size());
        assertTrue(stats.perChannel().get(0).max() > stats.perChannel().get(0).min());

        // Serialization
        String json = JsonUtil.toJson(JsonUtil.toMap(stats));
        assertTrue(json.contains("\"pixel_type\" : \"uint16\""));
        assertTrue(json.contains("\"channel\" : 0"));
    }

    @Test
    void exportToTiffEndToEnd(@TempDir Path tempDir) throws IOException {
        var file = Files.createFile(tempDir.resolve("input.tif"));
        // Output file doesn't exist yet — but the FakeImageWriter
        // captures writes in memory, so we use a fake writer here.
        var outputFile = tempDir.resolve("output.ome.tiff");

        var req = ExportToTiffTool.Request.of(
                file.toString(), outputFile.toString());
        var result = ExportToTiffTool.execute(
                req,
                PathValidator.allowAll(),
                fakeReader(),
                FakeImageWriter::new);

        assertInstanceOf(ToolResult.Success.class, result);
        var export = ((ToolResult.Success<ExportToTiffTool.ExportResult>) result).value();

        assertEquals(1, export.seriesExported());
        assertEquals(2, export.channelsPerSeries());
        assertEquals(5, export.zSlicesPerSeries());
        assertEquals(3, export.timepointsPerSeries());

        // Serialization
        String json = JsonUtil.toJson(JsonUtil.toMap(export));
        assertTrue(json.contains("\"series_exported\" : 1"));
    }

    // ================================================================
    // Error handling
    // ================================================================

    @Test
    void accessDeniedReturnsFailure() {
        PathValidator denyAll = rawPath ->
                new PathAccessControl.AccessResult.Denied("testing: access denied");

        var req = InspectImageTool.Request.of("/tmp/test.czi");
        var result = InspectImageTool.execute(
                req, denyAll, fakeReader());

        assertInstanceOf(ToolResult.Failure.class, result);
        var failure = (ToolResult.Failure<ImageMetadata>) result;
        assertEquals(ToolResult.ErrorKind.ACCESS_DENIED, failure.kind());
        assertTrue(failure.message().contains("access denied"));
    }

    @Test
    void invalidSeriesReturnsFailure(@TempDir Path tempDir) throws IOException {
        var file = Files.createFile(tempDir.resolve("test.tif"));

        var req = InspectImageTool.Request.of(
                file.toString(), 99, null, null, null);
        var result = InspectImageTool.execute(
                req, PathValidator.allowAll(), fakeReader());

        assertInstanceOf(ToolResult.Failure.class, result);
        var failure = (ToolResult.Failure<ImageMetadata>) result;
        assertEquals(ToolResult.ErrorKind.INVALID_ARGUMENT, failure.kind());
    }

    // ================================================================
    // Server constants
    // ================================================================

    @Test
    void serverNameAndVersionAreSet() {
        assertEquals("bioimage-mcp", BioImageMcpServer.NAME);
        assertFalse(BioImageMcpServer.VERSION.isEmpty());
    }
}
