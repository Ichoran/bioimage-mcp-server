package lab.kerrr.mcpbio.bioimageserver;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import org.apache.commons.cli.ParseException;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP/stdio adapter exposing the {@link BioImageService} as an MCP server
 * for AI agents working with microscopy image data.
 *
 * <p>This class is purely a transport: it declares the JSON schemas, wires
 * the stdio transport, translates MCP client roots into the service's
 * access policy, and maps each {@link ToolResult} onto an MCP
 * {@link CallToolResult}.  All image-serving logic lives in
 * {@link BioImageService}, which is shared with other adapters such as
 * {@link BioImageHttpService}.
 *
 * <p>Registers six tools: {@code inspect_image}, {@code get_ome_metadata},
 * {@code get_thumbnail}, {@code get_plane}, {@code get_intensity_stats}, and
 * {@code export_to_tiff}.
 *
 * @see BioImageService
 * @see PathAccessControl
 */
public class BioImageMcpServer {

    static final String NAME = "bioimage-mcp";
    static final String VERSION = "0.2.0";

    /**
     * Server-level instructions shown to the LLM when it discovers
     * this MCP server.  Written for LLM consumption — not end users.
     */
    static final String SERVER_INSTRUCTIONS = """
            BioImage MCP Server reads microscopy image files in 150+ formats \
            (CZI, ND2, LIF, OME-TIFF, etc.) via Bio-Formats and returns \
            structured metadata, visual previews, and intensity statistics.

            Recommended workflow:
            1. inspect_image first — learn dimensions, channels, pixel type, \
            and physical pixel sizes.  Start with 'summary' detail; request \
            'standard' for wavelengths/instrument info, 'full' only if \
            the user needs raw per-plane metadata.  For the complete, portable \
            metadata document (OME-XML), use get_ome_metadata.
            2. get_thumbnail for a quick RGB overview (auto-composites \
            channels, auto-projects Z).
            3. get_plane to examine a single channel/Z/timepoint at full \
            resolution (grayscale).
            4. get_intensity_stats for quantitative assessment — min/max, \
            mean, histogram, saturation warnings.  Adaptive mode reads \
            as much data as time and memory allow.
            5. export_to_tiff to convert to OME-TIFF for downstream tools, \
            or export_to_ngff to convert to OME-Zarr (OME-NGFF 0.5) for \
            large/cloud-native data (napari, zarr/dask, neuroglancer).

            Key points:
            - All paths must be absolute.
            - All indices are zero-based.
            - Large files are handled safely: tools subsample or time out \
            rather than hanging.  Check the response for notes about \
            subsampled data.
            - Errors are always returned as structured messages with an \
            error kind (ACCESS_DENIED, INVALID_ARGUMENT, IO_ERROR, TIMEOUT) \
            — never silently swallowed.
            - Image tools return base64 PNG.  Text tools return JSON.
            """;

    private final BioImageService service;

    private BioImageMcpServer(BioImageService service) {
        this.service = service;
    }

    // ================================================================
    // Builder (for use from the JBang runner)
    // ================================================================

    /**
     * Creates a server builder for configuring path access rules.
     *
     * <p>Example usage from a JBang runner:
     * <pre>{@code
     * BioImageMcpServer.builder()
     *     .allow("/data/microscopy")
     *     .deny("/data/microscopy/private")
     *     .build()
     *     .run(args);
     * }</pre>
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Thin builder delegating to {@link BioImageService.Builder}. */
    public static class Builder {
        private final BioImageService.Builder service = BioImageService.builder();

        /** Add a path that the server is explicitly allowed to access. */
        public Builder allow(String path) {
            service.allow(path);
            return this;
        }

        /** Add a path that the server must never access. */
        public Builder deny(String path) {
            service.deny(path);
            return this;
        }

        public BioImageMcpServer build() {
            return new BioImageMcpServer(service.build());
        }
    }

    // ================================================================
    // Server lifecycle
    // ================================================================

    /**
     * Start the MCP server on stdio.  This method returns once the
     * transport threads are running; they keep the JVM alive until the
     * client disconnects.
     *
     * <p>Supported command-line options:
     * <ul>
     *   <li>{@code --allow <path>} — grant access to a path (repeatable)</li>
     *   <li>{@code --deny <path>} — block access to a path (repeatable)</li>
     * </ul>
     * CLI paths are merged with any paths configured via the builder.
     */
    public void run(String[] args) {
        try {
            service.applyCliArgs(args);
        } catch (ParseException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }

        // Save stdout for MCP transport before any library can write to it.
        // Redirect System.out → System.err so Bio-Formats logging doesn't
        // corrupt the JSON-RPC protocol stream.
        var mcpOut = System.out;
        System.setOut(System.err);

        var mapper = McpJsonDefaults.getMapper();
        var transport = new StdioServerTransportProvider(mapper,
                System.in, mcpOut);

        var server = McpServer.sync(transport)
                .serverInfo(NAME, VERSION)
                .instructions(SERVER_INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .rootsChangeHandler(this::onRootsChanged)
                .tools(
                        inspectImageSpec(),
                        getOmeMetadataSpec(),
                        getThumbnailSpec(),
                        getPlaneSpec(),
                        getIntensityStatsSpec(),
                        exportToTiffSpec(),
                        exportToNgffSpec())
                .build();

        // The transport threads keep the JVM alive until the client
        // disconnects.  Add a shutdown hook for clean teardown.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.closeGracefully(); }
            catch (Exception e) { /* best effort */ }
        }));
    }

    public static void main(String[] args) {
        new BioImageMcpServer(BioImageService.builder().build()).run(args);
    }

    // ================================================================
    // Client roots handling
    // ================================================================

    private void onRootsChanged(McpSyncServerExchange exchange,
                                List<McpSchema.Root> roots) {
        var paths = new ArrayList<Path>();
        for (var root : roots) {
            String uri = root.uri();
            if (uri != null && uri.startsWith("file://")) {
                try {
                    paths.add(Path.of(URI.create(uri)));
                } catch (Exception e) {
                    System.err.println("WARN: ignoring invalid root URI: "
                            + uri + " (" + e.getMessage() + ")");
                }
            }
        }
        service.setClientRoots(paths);
    }

    // ================================================================
    // Tool specifications
    // ================================================================

    private SyncToolSpecification inspectImageSpec() {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("inspect_image")
                        .description("Read a microscopy file and return structured "
                                + "metadata as JSON. Always call this first to "
                                + "learn a file's dimensions (X/Y/Z/C/T), pixel "
                                + "type, physical pixel sizes, channel names, and "
                                + "series count. Use 'summary' for a quick look, "
                                + "'standard' to add wavelengths and instrument "
                                + "info, 'full' for all metadata. Supports 150+ "
                                + "formats (CZI, ND2, LIF, OME-TIFF, etc.).")
                        .inputSchema(inspectImageSchema())
                        .annotations(new McpSchema.ToolAnnotations(
                                null, true, false, true, false, false))
                        .build())
                .callHandler((exchange, request) ->
                        handleInspectImage(request.arguments()))
                .build();
    }

    private SyncToolSpecification getOmeMetadataSpec() {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("get_ome_metadata")
                        .description("Return the file's full extended metadata as a "
                                + "portable, format-tagged document: a 'format' "
                                + "identifier ('ome_xml', or 'ome_ngff' if the "
                                + "reader provides it) and the 'content' string "
                                + "(OME-XML / OME-NGFF JSON). This is the complete "
                                + "OME metadata Bio-Formats synthesizes — more "
                                + "exhaustive than inspect_image's parsed fields, "
                                + "and the most portable way to hand the metadata "
                                + "to other tools. Capped by max_response_bytes "
                                + "(default 256 KB) since it can be large; raise "
                                + "the cap to retrieve a bigger document.")
                        .inputSchema(getOmeMetadataSchema())
                        .annotations(new McpSchema.ToolAnnotations(
                                null, true, false, true, false, false))
                        .build())
                .callHandler((exchange, request) ->
                        handleGetOmeMetadata(request.arguments()))
                .build();
    }

    private SyncToolSpecification getThumbnailSpec() {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("get_thumbnail")
                        .description("Generate a quick RGB composite preview as "
                                + "a PNG image. Composites all channels using "
                                + "metadata colors and auto-projects through Z "
                                + "(adaptive mode tries max-intensity, falls back "
                                + "to mid-slice if too slow). Good for getting a "
                                + "visual overview before deeper analysis.")
                        .inputSchema(getThumbnailSchema())
                        .annotations(new McpSchema.ToolAnnotations(
                                null, true, false, true, false, false))
                        .build())
                .callHandler((exchange, request) ->
                        handleGetThumbnail(request.arguments()))
                .build();
    }

    private SyncToolSpecification getPlaneSpec() {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("get_plane")
                        .description("Extract a single 2D plane as a grayscale "
                                + "PNG image. Specify channel, z_slice, and "
                                + "timepoint to select which plane. Use this to "
                                + "examine one channel at a time at full "
                                + "resolution, or to compare specific Z-slices "
                                + "or timepoints. Auto-contrast by default "
                                + "(percentile stretch); set normalize=false "
                                + "for absolute intensity mapping.")
                        .inputSchema(getPlaneSchema())
                        .annotations(new McpSchema.ToolAnnotations(
                                null, true, false, true, false, false))
                        .build())
                .callHandler((exchange, request) ->
                        handleGetPlane(request.arguments()))
                .build();
    }

    private SyncToolSpecification getIntensityStatsSpec() {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("get_intensity_stats")
                        .description("Compute per-channel intensity statistics "
                                + "as JSON: min, max, mean, stddev, median, "
                                + "histogram, saturation warnings, and bit-depth "
                                + "utilization. Use for quality assessment — "
                                + "e.g. checking for saturated pixels, clipping, "
                                + "or underexposure. Supports single values "
                                + "(channel, z_slice, timepoint) or inclusive "
                                + "ranges (channel_start/end, z_start/end, "
                                + "t_start/end). Omit Z and T params for "
                                + "adaptive mode that reads as much data as "
                                + "the time budget allows.")
                        .inputSchema(getIntensityStatsSchema())
                        .annotations(new McpSchema.ToolAnnotations(
                                null, true, false, true, false, false))
                        .build())
                .callHandler((exchange, request) ->
                        handleGetIntensityStats(request.arguments()))
                .build();
    }

    private SyncToolSpecification exportToTiffSpec() {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("export_to_tiff")
                        .description("Convert microscopy data to OME-TIFF, the "
                                + "open standard readable by FIJI, napari, and "
                                + "most imaging software. Can subset by series, "
                                + "channels, Z-range, and time-range. Reports "
                                + "what metadata was preserved and warns about "
                                + "any losses from format conversion. Does not "
                                + "modify the original file.")
                        .inputSchema(exportToTiffSchema())
                        .annotations(new McpSchema.ToolAnnotations(
                                null, false, false, false, false, false))
                        .build())
                .callHandler((exchange, request) ->
                        handleExportToTiff(request.arguments()))
                .build();
    }

    private SyncToolSpecification exportToNgffSpec() {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("export_to_ngff")
                        .description("Convert microscopy data to OME-Zarr "
                                + "(OME-NGFF 0.5, Zarr v3), the cloud-native open "
                                + "format for large bioimaging data, readable by "
                                + "napari, zarr/dask, and neuroglancer. Streams to "
                                + "a chunked, compressed .zarr directory (not a "
                                + "single file), so it scales to very large "
                                + "volumes. Can subset by series, channels, "
                                + "Z-range, and time-range. The output directory "
                                + "must not already exist. Does not modify the "
                                + "original file.")
                        .inputSchema(exportToNgffSchema())
                        .annotations(new McpSchema.ToolAnnotations(
                                null, false, false, false, false, false))
                        .build())
                .callHandler((exchange, request) ->
                        handleExportToNgff(request.arguments()))
                .build();
    }

    // ================================================================
    // JSON schemas for tool inputs
    // ================================================================

    private static McpSchema.JsonSchema inspectImageSchema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("path", Map.of(
                "type", "string",
                "description", "Absolute path to the microscopy image file"));
        props.put("series", Map.of(
                "type", "integer",
                "description", "Zero-based series index (default: 0). "
                        + "Multi-series formats (CZI, LIF) may contain "
                        + "multiple image series."));
        props.put("detail", Map.of(
                "type", "string",
                "enum", List.of("summary", "standard", "full"),
                "description", "How much metadata to return. "
                        + "'summary': dimensions and pixel type. "
                        + "'standard' (default): adds channel wavelengths, "
                        + "instrument info, acquisition date. "
                        + "'full': all metadata including per-plane entries."));
        props.put("timeout_seconds", Map.of(
                "type", "integer",
                "description", "Wall-clock time limit in seconds (default: 30)"));
        props.put("max_response_bytes", Map.of(
                "type", "integer",
                "description", "Approximate cap on response size in bytes "
                        + "(default: 65536). If metadata exceeds this, "
                        + "the detail level is automatically downgraded."));
        return new McpSchema.JsonSchema(
                "object", props, List.of("path"), false, null, null);
    }

    /** Default size cap for get_ome_metadata on MCP (the document enters context). */
    private static final long DEFAULT_OME_METADATA_MAX_BYTES = 262144;  // 256 KB

    private static McpSchema.JsonSchema getOmeMetadataSchema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("path", Map.of(
                "type", "string",
                "description", "Absolute path to the microscopy image file"));
        props.put("max_response_bytes", Map.of(
                "type", "integer",
                "description", "Cap on the document size in bytes (default: "
                        + DEFAULT_OME_METADATA_MAX_BYTES + "). A document larger "
                        + "than this is an error reporting the actual size (it is "
                        + "never truncated); raise the cap to retrieve it."));
        return new McpSchema.JsonSchema(
                "object", props, List.of("path"), false, null, null);
    }

    private static McpSchema.JsonSchema getThumbnailSchema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("path", Map.of(
                "type", "string",
                "description", "Absolute path to the microscopy image file"));
        props.put("series", Map.of(
                "type", "integer",
                "description", "Zero-based series index (default: 0)"));
        props.put("projection", Map.of(
                "type", "string",
                "enum", List.of("mid_slice", "max_intensity", "sum", "adaptive"),
                "description", "Z-projection mode for Z-stacks. "
                        + "'adaptive' (default): tries max-intensity, "
                        + "falls back to mid-slice if too slow. "
                        + "'mid_slice': central Z-slice only. "
                        + "'max_intensity': max across all Z. "
                        + "'sum': sum across all Z."));
        props.put("channels", Map.of(
                "type", "string",
                "description", "Channel selection to composite (required). "
                        + SLICE_DESC));
        props.put("t", Map.of(
                "type", "integer",
                "description", "Zero-based timepoint index (default: 0; "
                        + "negative counts from the end)"));
        props.put("max_size", Map.of(
                "type", "integer",
                "description", "Maximum dimension in pixels for the output "
                        + "(default: 1024). Image is downsampled to fit."));
        props.put("timeout_seconds", Map.of(
                "type", "integer",
                "description", "Wall-clock time limit in seconds (default: 60)"));
        props.put("max_bytes", Map.of(
                "type", "integer",
                "description", "Approximate cap on raw pixel bytes to read "
                        + "(default: 512 MB)"));
        return new McpSchema.JsonSchema(
                "object", props, List.of("path", "channels"), false, null, null);
    }

    private static McpSchema.JsonSchema getPlaneSchema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("path", Map.of(
                "type", "string",
                "description", "Absolute path to the microscopy image file"));
        props.put("series", Map.of(
                "type", "integer",
                "description", "Zero-based series index (default: 0)"));
        props.put("channel", Map.of(
                "type", "integer",
                "description", "Zero-based channel index (default: 0; "
                        + "negative counts from the end)"));
        props.put("z", Map.of(
                "type", "integer",
                "description", "Zero-based Z-slice index (default: 0; "
                        + "negative counts from the end)"));
        props.put("t", Map.of(
                "type", "integer",
                "description", "Zero-based timepoint index (default: 0; "
                        + "negative counts from the end)"));
        props.put("normalize", Map.of(
                "type", "boolean",
                "description", "If true (default), auto-contrast via percentile "
                        + "stretch (0.1–99.9th). If false, map full type range "
                        + "to 0–255."));
        props.put("max_size", Map.of(
                "type", "integer",
                "description", "If provided, downsample so the largest dimension "
                        + "does not exceed this value"));
        props.put("timeout_seconds", Map.of(
                "type", "integer",
                "description", "Wall-clock time limit in seconds (default: 30)"));
        props.put("max_bytes", Map.of(
                "type", "integer",
                "description", "Approximate cap on raw pixel bytes to read "
                        + "(default: 256 MB)"));
        return new McpSchema.JsonSchema(
                "object", props, List.of("path"), false, null, null);
    }

    /** Shared description of the slice-selection grammar (snake_case-free). */
    private static final String SLICE_DESC =
            "A slice selection in Python style: a single index ('9', or '-1' "
            + "for the last), a half-open range ('2:4' = indices 2,3; '5:' = "
            + "5 to end; ':-3' = all but the last 3; ':' = all), or a "
            + "comma-separated list ('0,2,5' or '4:9,11:'). Explicit bounds "
            + "past the end are an error (no silent clamping); an empty "
            + "selection is an error.";

    private static McpSchema.JsonSchema getIntensityStatsSchema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("path", Map.of(
                "type", "string",
                "description", "Absolute path to the microscopy image file"));
        props.put("series", Map.of(
                "type", "integer",
                "description", "Zero-based series index (default: 0)"));
        props.put("channels", Map.of(
                "type", "string",
                "description", "Channel selection (required). " + SLICE_DESC));
        props.put("z", Map.of(
                "type", "string",
                "description", "Z-slice selection (required). " + SLICE_DESC
                        + " Use ':' to read all Z adaptively within the budget."));
        props.put("t", Map.of(
                "type", "string",
                "description", "Timepoint selection (required). " + SLICE_DESC
                        + " Use ':' to read all timepoints adaptively within "
                        + "the budget."));
        props.put("histogram_bins", Map.of(
                "type", "integer",
                "description", "Number of histogram bins (default: 256)"));
        props.put("timeout_seconds", Map.of(
                "type", "integer",
                "description", "Wall-clock time limit in seconds (default: 60)"));
        props.put("max_bytes", Map.of(
                "type", "integer",
                "description", "Approximate cap on raw pixel bytes to read "
                        + "(default: 512 MB)"));
        return new McpSchema.JsonSchema(
                "object", props, List.of("path", "channels", "z", "t"),
                false, null, null);
    }

    private static McpSchema.JsonSchema exportToTiffSchema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("path", Map.of(
                "type", "string",
                "description", "Absolute path to the source image file"));
        props.put("output_path", Map.of(
                "type", "string",
                "description", "Absolute path for the output OME-TIFF file"));
        props.put("series", Map.of(
                "type", "integer",
                "description", "Series to export (default: all series)"));
        props.put("channels", Map.of(
                "type", "string",
                "description", "Channel selection (required). " + SLICE_DESC));
        props.put("z", Map.of(
                "type", "string",
                "description", "Z-slice selection (required); must be a single "
                        + "contiguous range (e.g. ':' or '0:10'). " + SLICE_DESC));
        props.put("t", Map.of(
                "type", "string",
                "description", "Timepoint selection (required); must be a single "
                        + "contiguous range (e.g. ':' or '0:10'). " + SLICE_DESC));
        props.put("compression", Map.of(
                "type", "string",
                "enum", List.of("none", "lzw", "zlib"),
                "description", "Output compression (default: none)"));
        props.put("metadata_mode", Map.of(
                "type", "string",
                "enum", List.of("all", "structured", "minimal"),
                "description", "How much metadata to preserve. "
                        + "'all' (default): full OME-XML including original metadata. "
                        + "'structured': OME schema elements only. "
                        + "'minimal': Pixels/Channel/TiffData only."));
        props.put("timeout_seconds", Map.of(
                "type", "integer",
                "description", "Wall-clock time limit in seconds (default: 300)"));
        props.put("max_bytes", Map.of(
                "type", "integer",
                "description", "Approximate cap on raw pixel bytes to "
                        + "read+write (default: 2 GB)"));
        return new McpSchema.JsonSchema(
                "object", props, List.of("path", "output_path", "channels", "z", "t"),
                false, null, null);
    }

    private static McpSchema.JsonSchema exportToNgffSchema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("path", Map.of(
                "type", "string",
                "description", "Absolute path to the source image file"));
        props.put("output_path", Map.of(
                "type", "string",
                "description", "Absolute path for the output .zarr directory "
                        + "(must not already exist)"));
        props.put("series", Map.of(
                "type", "integer",
                "description", "Series to export (default: all series)"));
        props.put("channels", Map.of(
                "type", "string",
                "description", "Channel selection (required). " + SLICE_DESC));
        props.put("z", Map.of(
                "type", "string",
                "description", "Z-slice selection (required); must be a single "
                        + "contiguous range (e.g. ':' or '0:10'). " + SLICE_DESC));
        props.put("t", Map.of(
                "type", "string",
                "description", "Timepoint selection (required); must be a single "
                        + "contiguous range (e.g. ':' or '0:10'). " + SLICE_DESC));
        props.put("codec", Map.of(
                "type", "string",
                "enum", List.of("none", "gzip", "zstd", "blosc"),
                "description", "Chunk compression codec (default: zstd at "
                        + "level 5, a good speed/size balance). 'none' is "
                        + "fastest/largest; 'gzip' is pure-Java; 'blosc' is "
                        + "lz4 + byte-shuffle (very fast, near-free); 'zstd' "
                        + "compresses best at a given speed."));
        props.put("compression_level", Map.of(
                "type", "integer",
                "description", "Compression effort, trading speed against size. "
                        + "Default: zstd uses level 5; blosc uses clevel 5; "
                        + "gzip uses its library default. Lower is "
                        + "faster and larger; higher is slower and smaller. "
                        + "For microscopy, speed is often the priority, so a low "
                        + "level (or codec 'none') is a reasonable default. "
                        + "Valid ranges: gzip 0-9, zstd -7 to 22 (negative "
                        + "levels are the fastest), blosc 0-9. Not applicable "
                        + "to codec 'none'."));
        props.put("timeout_seconds", Map.of(
                "type", "integer",
                "description", "Wall-clock time limit in seconds (default: 1800)"));
        props.put("max_bytes", Map.of(
                "type", "integer",
                "description", "Approximate cap on raw pixel bytes to read+write "
                        + "(default: 100 GB). A guard, not a memory bound — the "
                        + "export streams to disk; raise it for larger datasets."));
        return new McpSchema.JsonSchema(
                "object", props, List.of("path", "output_path", "channels", "z", "t"),
                false, null, null);
    }

    // ================================================================
    // Tool handlers — delegate to the service, map ToolResult → MCP
    // ================================================================

    private CallToolResult handleInspectImage(Map<String, Object> args) {
        return switch (service.inspectImage(args)) {
            case ToolResult.Success<ImageMetadata> s ->
                jsonResult(JsonUtil.toMap(s.value()));
            case ToolResult.Failure<ImageMetadata> f -> errorResult(f);
        };
    }

    private CallToolResult handleGetOmeMetadata(Map<String, Object> args) {
        // Apply a conservative default size cap for MCP (the document goes into
        // the LLM context); callers can override max_response_bytes.
        if (!args.containsKey("max_response_bytes")) {
            args = new LinkedHashMap<>(args);
            args.put("max_response_bytes", DEFAULT_OME_METADATA_MAX_BYTES);
        }
        return switch (service.getOmeMetadata(args)) {
            case ToolResult.Success<OmeMetadata> s -> jsonResult(JsonUtil.toMap(s.value()));
            case ToolResult.Failure<OmeMetadata> f -> errorResult(f);
        };
    }

    private CallToolResult handleGetThumbnail(Map<String, Object> args) {
        return switch (service.getThumbnail(args)) {
            case ToolResult.Success<GetThumbnailTool.ThumbnailResult> s -> {
                var tr = s.value();
                var image = pngContent(tr.png());
                var text = new McpSchema.TextContent(
                        JsonUtil.toJson(JsonUtil.toMap(tr)));
                yield CallToolResult.builder()
                        .addContent(image)
                        .addContent(text)
                        .build();
            }
            case ToolResult.Failure<GetThumbnailTool.ThumbnailResult> f ->
                errorResult(f);
        };
    }

    private CallToolResult handleGetPlane(Map<String, Object> args) {
        return switch (service.getPlane(args)) {
            case ToolResult.Success<byte[]> s ->
                CallToolResult.builder()
                        .addContent(pngContent(s.value()))
                        .build();
            case ToolResult.Failure<byte[]> f -> errorResult(f);
        };
    }

    private CallToolResult handleGetIntensityStats(Map<String, Object> args) {
        return switch (service.getIntensityStats(args)) {
            case ToolResult.Success<GetIntensityStatsTool.StatsResult> s ->
                jsonResult(JsonUtil.toMap(s.value()));
            case ToolResult.Failure<GetIntensityStatsTool.StatsResult> f ->
                errorResult(f);
        };
    }

    private CallToolResult handleExportToTiff(Map<String, Object> args) {
        return switch (service.exportToTiff(args)) {
            case ToolResult.Success<ExportToTiffTool.ExportResult> s ->
                jsonResult(JsonUtil.toMap(s.value()));
            case ToolResult.Failure<ExportToTiffTool.ExportResult> f ->
                errorResult(f);
        };
    }

    private CallToolResult handleExportToNgff(Map<String, Object> args) {
        return switch (service.exportToNgff(args)) {
            case ToolResult.Success<ExportToNgffTool.NgffResult> s ->
                jsonResult(JsonUtil.toMap(s.value()));
            case ToolResult.Failure<ExportToNgffTool.NgffResult> f ->
                errorResult(f);
        };
    }

    // ================================================================
    // ToolResult → CallToolResult mapping
    // ================================================================

    private static CallToolResult jsonResult(Map<String, Object> value) {
        return CallToolResult.builder()
                .addTextContent(JsonUtil.toJson(value))
                .build();
    }

    private static McpSchema.ImageContent pngContent(byte[] png) {
        return new McpSchema.ImageContent(
                null, Base64.getEncoder().encodeToString(png), "image/png");
    }

    private static <T> CallToolResult errorResult(ToolResult.Failure<T> f) {
        return CallToolResult.builder()
                .addTextContent("[" + f.kind().name() + "] " + f.message())
                .isError(true)
                .build();
    }
}
