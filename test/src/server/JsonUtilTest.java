package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.Compression;
import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.ExportResult;
import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.MetadataMode;
import lab.kerrr.mcpbio.bioimageserver.GetIntensityStatsTool.Range;
import lab.kerrr.mcpbio.bioimageserver.GetIntensityStatsTool.StatsResult;
import lab.kerrr.mcpbio.bioimageserver.GetThumbnailTool.Projection;
import lab.kerrr.mcpbio.bioimageserver.GetThumbnailTool.ThumbnailResult;
import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.SeriesSummary;
import lab.kerrr.mcpbio.bioimageserver.PixelSize.LengthUnit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class JsonUtilTest {

    // ================================================================
    // ImageMetadata serialization
    // ================================================================

    @Test
    void imageMetadataToJson() {
        var channel = ChannelInfo.of(0, "DAPI", "DAPI",
                358.0, 461.0, 0xFF0000FF);
        var series = new SeriesInfo(
                "Scene #0",
                512, 512, 10, 1, 1,
                PixelType.UINT16, "XYCZT",
                PixelSize.of(0.065, LengthUnit.MICROMETER),
                PixelSize.of(0.065, LengthUnit.MICROMETER),
                null,
                List.of(channel), null, "2024-01-15T10:30:00Z",
                Map.of());
        var summary = new SeriesSummary(0, "Scene #0",
                512, 512, 10, 1, 1);
        var metadata = new ImageMetadata(
                "Zeiss CZI", List.of(summary), 0, series,
                DetailLevel.STANDARD, 1234);

        String json = JsonUtil.toJson(JsonUtil.toMap(metadata));

        // Verify key fields are present
        assertTrue(json.contains("\"format\" : \"Zeiss CZI\""));
        assertTrue(json.contains("\"series_count\" : 1"));
        assertTrue(json.contains("\"pixel_type\" : \"uint16\""));
        assertTrue(json.contains("\"detail_level\" : \"standard\""));
        assertTrue(json.contains("\"omitted_metadata_bytes\" : 1234"));
        assertTrue(json.contains("\"sizeX\" : 512"));
        assertTrue(json.contains("\"DAPI\""));
        assertTrue(json.contains("\"excitation_wavelength_nm\" : 358.0"));
        assertTrue(json.contains("\"emission_wavelength_nm\" : 461.0"));
        assertTrue(json.contains("\"physical_size_x\""));
        assertTrue(json.contains("\"acquisition_date\" : \"2024-01-15T10:30:00Z\""));
    }

    @Test
    void imageMetadataOmitsNulls() {
        var series = new SeriesInfo(
                null, 100, 100, 1, 1, 1,
                PixelType.UINT8, "XYCZT",
                null, null, null,
                List.of(), null, null, Map.of());
        var metadata = new ImageMetadata(
                "TIFF", List.of(), 0, series,
                DetailLevel.SUMMARY, 0);

        String json = JsonUtil.toJson(JsonUtil.toMap(metadata));

        assertFalse(json.contains("\"name\""));
        assertFalse(json.contains("physical_size"));
        assertFalse(json.contains("instrument"));
        assertFalse(json.contains("acquisition_date"));
        assertFalse(json.contains("omitted_metadata_bytes"));
    }

    @Test
    void instrumentInfoToJson() {
        var inst = new InstrumentInfo(
                "Plan-Apo 63x", "Zeiss", 63.0, 62.8, 1.4, "Oil", "PlanApo");
        var map = JsonUtil.toMap(inst);

        assertNotNull(map);
        assertEquals("Plan-Apo 63x", map.get("objective_model"));
        assertEquals("Zeiss", map.get("manufacturer"));
        assertEquals(63.0, map.get("nominal_magnification"));
        assertEquals(1.4, map.get("numerical_aperture"));
        assertEquals("Oil", map.get("immersion"));
    }

    @Test
    void emptyInstrumentReturnsNull() {
        var inst = new InstrumentInfo(null, null, null, null, null, null, null);
        assertNull(JsonUtil.toMap(inst));
    }

    @Test
    void channelColorFormattedAsHex() {
        var ch = ChannelInfo.of(0, "GFP", null, 488.0, 525.0, 0xFF00FF00);
        var map = JsonUtil.toMap(ch);
        assertEquals("#FF00FF00", map.get("color_argb"));
    }

    // ================================================================
    // StatsResult serialization
    // ================================================================

    @Test
    void statsResultToJson() {
        var stats = new IntensityStats(
                0, 10.0, 200.0, 105.0, 55.0, 100.0,
                new double[] { 0, 128, 256 },
                new long[] { 1000, 2000 },
                0.01, 0.005, 0.75,
                true, 0.5);
        var result = new StatsResult(
                List.of(stats),
                new Range(0, 0),
                new Range(0, 9),
                new Range(0, 0),
                new int[] { 0, 2, 5, 9 },
                new int[] { 0 },
                PixelType.UINT8);

        String json = JsonUtil.toJson(JsonUtil.toMap(result));

        assertTrue(json.contains("\"pixel_type\" : \"uint8\""));
        assertTrue(json.contains("\"channel\" : 0"));
        assertTrue(json.contains("\"min\" : 10.0"));
        assertTrue(json.contains("\"sampled\" : true"));
        assertTrue(json.contains("\"sampled_fraction\" : 0.5"));
        assertTrue(json.contains("\"z_slices_used\""));
    }

    @Test
    void unsampledStatsOmitsSampledFraction() {
        var stats = new IntensityStats(
                0, 0.0, 255.0, 128.0, 50.0, 127.0,
                new double[] { 0, 256 },
                new long[] { 1000 },
                0.0, 0.0, 1.0,
                false, 1.0);
        var map = JsonUtil.toMap(stats);

        assertEquals(false, map.get("sampled"));
        assertFalse(map.containsKey("sampled_fraction"));
    }

    // ================================================================
    // ExportResult serialization
    // ================================================================

    @Test
    void exportResultToJson() {
        var result = new ExportResult(
                "/tmp/output.ome.tiff",
                1024 * 1024,
                1, 2, 10, 1, 20,
                Compression.LZW,
                MetadataMode.STRUCTURED,
                "Zeiss CZI",
                true, 0, 50,
                List.of("Source format: Zeiss CZI."));

        String json = JsonUtil.toJson(JsonUtil.toMap(result));

        assertTrue(json.contains("\"output_path\" : \"/tmp/output.ome.tiff\""));
        assertTrue(json.contains("\"compression\" : \"lzw\""));
        assertTrue(json.contains("\"metadata_mode\" : \"structured\""));
        assertTrue(json.contains("\"source_format\" : \"Zeiss CZI\""));
        assertTrue(json.contains("\"warnings\""));
    }

    @Test
    void exportResultNoWarningsOmitsField() {
        var result = new ExportResult(
                "/tmp/out.ome.tiff", 100, 1, 1, 1, 1, 1,
                Compression.NONE, MetadataMode.ALL, "OME-TIFF",
                true, 0, 0, List.of());
        var map = JsonUtil.toMap(result);
        assertFalse(map.containsKey("warnings"));
    }

    // ================================================================
    // ThumbnailResult serialization
    // ================================================================

    @Test
    void thumbnailResultToJson() {
        var result = new ThumbnailResult(new byte[] { 1, 2, 3 },
                Projection.MAX_INTENSITY);
        String json = JsonUtil.toJson(JsonUtil.toMap(result));
        assertTrue(json.contains("\"projection_used\" : \"max_intensity\""));
    }

    // ================================================================
    // JSON output validity
    // ================================================================

    @Test
    void jsonOutputIsValidStructure() {
        // Ensure the JSON output starts with { and ends with }
        var series = new SeriesInfo(
                null, 1, 1, 1, 1, 1,
                PixelType.UINT8, "XYCZT",
                null, null, null,
                List.of(), null, null, Map.of());
        var metadata = new ImageMetadata(
                "TIFF", List.of(), 0, series,
                DetailLevel.SUMMARY, 0);

        String json = JsonUtil.toJson(JsonUtil.toMap(metadata));
        assertTrue(json.trim().startsWith("{"));
        assertTrue(json.trim().endsWith("}"));
    }

    @Test
    void specialCharactersInStringsAreEscaped() {
        var series = new SeriesInfo(
                "test \"quoted\" and\nnewline", 1, 1, 1, 1, 1,
                PixelType.UINT8, "XYCZT",
                null, null, null,
                List.of(), null, null, Map.of());
        var metadata = new ImageMetadata(
                "format with \"quotes\"",
                List.of(new SeriesSummary(0, "test \"quoted\" and\nnewline",
                        1, 1, 1, 1, 1)),
                0, series, DetailLevel.SUMMARY, 0);

        String json = JsonUtil.toJson(JsonUtil.toMap(metadata));

        // Jackson handles escaping; just verify it doesn't throw
        // and the output contains escaped sequences
        assertTrue(json.contains("\\\"quoted\\\""));
        assertTrue(json.contains("\\n"));
    }
}
