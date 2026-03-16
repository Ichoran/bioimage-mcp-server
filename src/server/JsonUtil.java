package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.GetIntensityStatsTool.StatsResult;
import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.ExportResult;
import lab.kerrr.mcpbio.bioimageserver.GetThumbnailTool.ThumbnailResult;
import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.SeriesSummary;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts domain records to JSON strings via Jackson 3.x.
 *
 * <p>Each domain type gets a {@code toMap} method that produces a
 * {@code Map<String, Object>} with clean, LLM-friendly keys (snake_case).
 * Jackson handles the final serialization to a JSON string, so we get
 * correct escaping, number formatting, and structure for free.
 *
 * <p>Null values are omitted from the output maps so the JSON stays
 * compact and uncluttered.
 */
final class JsonUtil {

    private JsonUtil() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectWriter WRITER =
            MAPPER.writer(SerializationFeature.INDENT_OUTPUT);

    /** Serialize any Map/List/primitive tree to a JSON string. */
    static String toJson(Object value) {
        try {
            return WRITER.writeValueAsString(value);
        } catch (Exception e) {
            // Should never happen for well-formed Map trees
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    // ================================================================
    // ImageMetadata
    // ================================================================

    static Map<String, Object> toMap(ImageMetadata m) {
        var map = new LinkedHashMap<String, Object>();
        map.put("format", m.formatName());
        map.put("series_count", m.allSeries().size());
        map.put("all_series", m.allSeries().stream()
                .map(JsonUtil::toMap).toList());
        map.put("detailed_series_index", m.detailedSeriesIndex());
        map.put("detailed_series", toMap(m.detailedSeries()));
        map.put("detail_level", m.detailLevel().name().toLowerCase());
        if (m.omittedMetadataBytes() > 0) {
            map.put("omitted_metadata_bytes", m.omittedMetadataBytes());
        }
        return map;
    }

    static Map<String, Object> toMap(SeriesSummary s) {
        var map = new LinkedHashMap<String, Object>();
        map.put("index", s.index());
        putIfNotNull(map, "name", s.name());
        map.put("sizeX", s.sizeX());
        map.put("sizeY", s.sizeY());
        map.put("sizeZ", s.sizeZ());
        map.put("sizeC", s.sizeC());
        map.put("sizeT", s.sizeT());
        return map;
    }

    static Map<String, Object> toMap(SeriesInfo si) {
        var map = new LinkedHashMap<String, Object>();
        putIfNotNull(map, "name", si.name());
        map.put("sizeX", si.sizeX());
        map.put("sizeY", si.sizeY());
        map.put("sizeZ", si.sizeZ());
        map.put("sizeC", si.sizeC());
        map.put("sizeT", si.sizeT());
        map.put("pixel_type", si.pixelType().name().toLowerCase());
        map.put("dimension_order", si.dimensionOrder());
        putIfNotNull(map, "physical_size_x", toMap(si.physicalSizeX()));
        putIfNotNull(map, "physical_size_y", toMap(si.physicalSizeY()));
        putIfNotNull(map, "physical_size_z", toMap(si.physicalSizeZ()));
        if (!si.channels().isEmpty()) {
            map.put("channels", si.channels().stream()
                    .map(JsonUtil::toMap).toList());
        }
        if (si.instrument() != null && !si.instrument().isEmpty()) {
            map.put("instrument", toMap(si.instrument()));
        }
        putIfNotNull(map, "acquisition_date", si.acquisitionDate());
        if (!si.extraMetadata().isEmpty()) {
            map.put("extra_metadata", si.extraMetadata());
        }
        return map;
    }

    static Map<String, Object> toMap(PixelSize ps) {
        if (ps == null) return null;
        var map = new LinkedHashMap<String, Object>();
        map.put("value", ps.value().doubleValue());
        map.put("unit", ps.unit().symbol);
        return map;
    }

    static Map<String, Object> toMap(ChannelInfo ch) {
        var map = new LinkedHashMap<String, Object>();
        map.put("index", ch.index());
        putIfNotNull(map, "name", ch.name());
        putIfNotNull(map, "fluor", ch.fluor());
        if (ch.excitationWavelength().isPresent()) {
            map.put("excitation_wavelength_nm", ch.excitationWavelength().getAsDouble());
        }
        if (ch.emissionWavelength().isPresent()) {
            map.put("emission_wavelength_nm", ch.emissionWavelength().getAsDouble());
        }
        if (ch.color().isPresent()) {
            map.put("color_argb", String.format("#%08X", ch.color().getAsInt()));
        }
        return map;
    }

    static Map<String, Object> toMap(InstrumentInfo inst) {
        if (inst == null || inst.isEmpty()) return null;
        var map = new LinkedHashMap<String, Object>();
        putIfNotNull(map, "objective_model", inst.objectiveModel());
        putIfNotNull(map, "manufacturer", inst.manufacturer());
        putIfNotNull(map, "nominal_magnification", inst.nominalMagnification());
        putIfNotNull(map, "calibrated_magnification", inst.calibratedMagnification());
        putIfNotNull(map, "numerical_aperture", inst.numericalAperture());
        putIfNotNull(map, "immersion", inst.immersion());
        putIfNotNull(map, "correction", inst.correction());
        return map;
    }

    // ================================================================
    // IntensityStats / StatsResult
    // ================================================================

    static Map<String, Object> toMap(StatsResult r) {
        var map = new LinkedHashMap<String, Object>();
        map.put("pixel_type", r.pixelType().name().toLowerCase());
        map.put("channels", toMap(r.channels()));
        map.put("z_range", toMap(r.zRange()));
        map.put("t_range", toMap(r.tRange()));
        map.put("z_slices_used", r.zSlicesUsed());
        map.put("timepoints_used", r.timepointsUsed());
        map.put("per_channel", r.perChannel().stream()
                .map(JsonUtil::toMap).toList());
        return map;
    }

    static Map<String, Object> toMap(GetIntensityStatsTool.Range range) {
        if (range == null) return null;
        var map = new LinkedHashMap<String, Object>();
        map.put("start", range.start());
        map.put("end", range.end());
        return map;
    }

    static Map<String, Object> toMap(IntensityStats s) {
        var map = new LinkedHashMap<String, Object>();
        map.put("channel", s.channel());
        map.put("min", s.min());
        map.put("max", s.max());
        map.put("mean", s.mean());
        map.put("stddev", s.stddev());
        map.put("median", s.median());
        map.put("histogram_bin_edges", s.histogramBinEdges());
        map.put("histogram_counts", s.histogramCounts());
        map.put("saturation_fraction_low", s.saturationFractionLow());
        map.put("saturation_fraction_high", s.saturationFractionHigh());
        map.put("bit_depth_utilization", s.bitDepthUtilization());
        map.put("sampled", s.sampled());
        if (s.sampled()) {
            map.put("sampled_fraction", s.sampledFraction());
        }
        return map;
    }

    // ================================================================
    // ExportResult
    // ================================================================

    static Map<String, Object> toMap(ExportResult r) {
        var map = new LinkedHashMap<String, Object>();
        map.put("output_path", r.outputPath());
        map.put("bytes_written", r.bytesWritten());
        map.put("series_exported", r.seriesExported());
        map.put("channels_per_series", r.channelsPerSeries());
        map.put("z_slices_per_series", r.zSlicesPerSeries());
        map.put("timepoints_per_series", r.timepointsPerSeries());
        map.put("total_planes_written", r.totalPlanesWritten());
        map.put("compression", r.compression().name().toLowerCase());
        map.put("metadata_mode", r.metadataMode().name().toLowerCase());
        map.put("source_format", r.sourceFormat());
        map.put("ome_xml_present", r.omeXmlPresent());
        if (r.originalMetadataInXml() > 0) {
            map.put("original_metadata_in_xml", r.originalMetadataInXml());
        }
        if (r.originalMetadataInReader() > 0) {
            map.put("original_metadata_in_reader", r.originalMetadataInReader());
        }
        if (!r.warnings().isEmpty()) {
            map.put("warnings", r.warnings());
        }
        return map;
    }

    // ================================================================
    // ThumbnailResult (text metadata only; image is sent separately)
    // ================================================================

    static Map<String, Object> toMap(ThumbnailResult r) {
        var map = new LinkedHashMap<String, Object>();
        map.put("projection_used", r.projectionUsed().name().toLowerCase());
        return map;
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static void putIfNotNull(Map<String, Object> map,
                                      String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
