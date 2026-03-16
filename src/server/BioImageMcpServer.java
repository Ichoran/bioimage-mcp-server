package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.Compression;
import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.MetadataMode;
import lab.kerrr.mcpbio.bioimageserver.GetIntensityStatsTool.Range;
import lab.kerrr.mcpbio.bioimageserver.GetThumbnailTool.Projection;
import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * MCP server that provides Bio-Formats reader and metadata capability
 * for AI agents working with microscopy image data.
 *
 * <p>Registers five tools: {@code inspect_image}, {@code get_thumbnail},
 * {@code get_plane}, {@code get_intensity_stats}, and {@code export_to_tiff}.
 * Uses stdio transport for communication with the MCP client.
 *
 * <p>File access is controlled by a three-tier system:
 * <ol>
 *   <li>Deny list — always blocked</li>
 *   <li>Allow list — explicitly permitted by server configuration</li>
 *   <li>Client roots — paths declared by the MCP client</li>
 * </ol>
 *
 * @see PathAccessControl
 */
public class BioImageMcpServer {

    static final String NAME = "bioimage-mcp";
    static final String VERSION = "0.1.1";

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
            the user needs raw per-plane metadata.
            2. get_thumbnail for a quick RGB overview (auto-composites \
            channels, auto-projects Z).
            3. get_plane to examine a single channel/Z/timepoint at full \
            resolution (grayscale).
            4. get_intensity_stats for quantitative assessment — min/max, \
            mean, histogram, saturation warnings.  Adaptive mode reads \
            as much data as time and memory allow.
            5. export_to_tiff to convert to OME-TIFF for downstream tools.

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

    /** Deny/allow lists — immutable snapshots, replaced when CLI args are parsed. */
    private List<Path> denyList;
    private List<Path> allowList;

    /** Client roots, updated when the MCP client sends a roots notification. */
    private final CopyOnWriteArrayList<Path> clientRoots = new CopyOnWriteArrayList<>();

    /** The current path access control, rebuilt when client roots change. */
    private volatile PathAccessControl accessControl;

    private BioImageMcpServer(List<Path> denyList, List<Path> allowList) {
        this.denyList = List.copyOf(denyList);
        this.allowList = List.copyOf(allowList);
        rebuildAccessControl();
    }

    /** Return a new immutable list with one path appended. */
    private static List<Path> appendPath(List<Path> existing, String path) {
        var merged = new ArrayList<>(existing);
        merged.add(Path.of(path));
        return List.copyOf(merged);
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

    public static class Builder {
        private final List<Path> denyList = new ArrayList<>();
        private final List<Path> allowList = new ArrayList<>();

        /** Add a path that the server is explicitly allowed to access. */
        public Builder allow(String path) {
            allowList.add(Path.of(path));
            return this;
        }

        /** Add a path that the server must never access. */
        public Builder deny(String path) {
            denyList.add(Path.of(path));
            return this;
        }

        public BioImageMcpServer build() {
            return new BioImageMcpServer(denyList, allowList);
        }
    }

    // ================================================================
    // Server lifecycle
    // ================================================================

    // ================================================================
    // Command-line options
    // ================================================================

    private static Options cliOptions() {
        var options = new Options();
        options.addOption(Option.builder()
                .longOpt("allow")
                .hasArg()
                .desc("Allow access to this path (may be repeated)")
                .build());
        options.addOption(Option.builder()
                .longOpt("deny")
                .hasArg()
                .desc("Deny access to this path (may be repeated)")
                .build());
        return options;
    }

    /**
     * Start the MCP server on stdio.  This method blocks until the
     * transport shuts down (typically when the client disconnects).
     *
     * <p>Supported command-line options:
     * <ul>
     *   <li>{@code --allow <path>} — grant access to a path (repeatable)</li>
     *   <li>{@code --deny <path>} — block access to a path (repeatable)</li>
     * </ul>
     * CLI paths are merged with any paths configured via the builder.
     */
    public void run(String[] args) {
        // Parse CLI args and merge with builder-configured paths.
        try {
            var cli = new DefaultParser().parse(cliOptions(), args);
            var extraAllow = cli.getOptionValues("allow");
            var extraDeny = cli.getOptionValues("deny");
            if (extraAllow != null) {
                for (var p : extraAllow) {
                    allowList = appendPath(allowList, p);
                }
            }
            if (extraDeny != null) {
                for (var p : extraDeny) {
                    denyList = appendPath(denyList, p);
                }
            }
            rebuildAccessControl();
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
                        getThumbnailSpec(),
                        getPlaneSpec(),
                        getIntensityStatsSpec(),
                        exportToTiffSpec())
                .build();

        // The transport threads keep the JVM alive until the client
        // disconnects.  Add a shutdown hook for clean teardown.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.closeGracefully(); }
            catch (Exception e) { /* best effort */ }
        }));
    }

    public static void main(String[] args) {
        new BioImageMcpServer(List.of(), List.of()).run(args);
    }

    // ================================================================
    // Client roots handling
    // ================================================================

    private void onRootsChanged(McpSyncServerExchange exchange,
                                List<McpSchema.Root> roots) {
        clientRoots.clear();
        for (var root : roots) {
            String uri = root.uri();
            if (uri != null && uri.startsWith("file://")) {
                try {
                    clientRoots.add(Path.of(URI.create(uri)));
                } catch (Exception e) {
                    System.err.println("WARN: ignoring invalid root URI: "
                            + uri + " (" + e.getMessage() + ")");
                }
            }
        }
        rebuildAccessControl();
    }

    private void rebuildAccessControl() {
        try {
            accessControl = new PathAccessControl(
                    denyList, allowList, List.copyOf(clientRoots));
        } catch (IOException e) {
            System.err.println("ERROR: failed to rebuild path access control: "
                    + e.getMessage());
            // Keep the previous access control if we have one
        }
    }

    private PathValidator pathValidator() {
        return accessControl::check;
    }

    /**
     * PathValidator for output files that checks the parent directory
     * (since the output file itself doesn't exist yet).
     */
    private PathValidator outputPathValidator() {
        return rawPath -> {
            var parent = Path.of(rawPath).getParent();
            if (parent == null) {
                return new AccessResult.Denied(
                        "Cannot determine parent directory of: " + rawPath);
            }
            var parentResult = accessControl.check(parent.toString());
            if (parentResult instanceof AccessResult.Denied) {
                return parentResult;
            }
            // Return the original (non-resolved) path since the file
            // doesn't exist yet — the tool will create it.
            return new AccessResult.Allowed(Path.of(rawPath).toAbsolutePath());
        };
    }

    // ================================================================
    // Reader/writer factories
    // ================================================================

    private static Supplier<ImageReader> readerFactory() {
        return BioFormatsReader::new;
    }

    private static Supplier<ImageWriter> writerFactory() {
        return BioFormatsWriter::new;
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
                "type", "array",
                "items", Map.of("type", "integer"),
                "description", "Which channels to include (default: all). "
                        + "Zero-based indices."));
        props.put("timepoint", Map.of(
                "type", "integer",
                "description", "Zero-based timepoint index (default: 0)"));
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
                "object", props, List.of("path"), false, null, null);
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
                "description", "Zero-based channel index (default: 0)"));
        props.put("z_slice", Map.of(
                "type", "integer",
                "description", "Zero-based Z-slice index (default: 0)"));
        props.put("timepoint", Map.of(
                "type", "integer",
                "description", "Zero-based timepoint index (default: 0)"));
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

    private static McpSchema.JsonSchema getIntensityStatsSchema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("path", Map.of(
                "type", "string",
                "description", "Absolute path to the microscopy image file"));
        props.put("series", Map.of(
                "type", "integer",
                "description", "Zero-based series index (default: 0)"));
        props.put("channel", Map.of(
                "type", "integer",
                "description", "Specific channel to analyze (default: all channels). "
                        + "Negative values count from the end (-1 = last). "
                        + "Cannot be used together with channel_start/channel_end."));
        props.put("channel_start", Map.of(
                "type", "integer",
                "description", "First channel, inclusive (default: 0). "
                        + "Negative values count from the end (-1 = last). "
                        + "Use with channel_end for a range. "
                        + "Cannot be used together with channel."));
        props.put("channel_end", Map.of(
                "type", "integer",
                "description", "Last channel, inclusive (default: -1, i.e. last). "
                        + "Negative values count from the end (-1 = last). "
                        + "Use with channel_start for a range. "
                        + "Cannot be used together with channel."));
        props.put("z_slice", Map.of(
                "type", "integer",
                "description", "Specific Z-slice (default: adaptive across all Z). "
                        + "Negative values count from the end (-1 = last). "
                        + "Cannot be used together with z_start/z_end."));
        props.put("z_start", Map.of(
                "type", "integer",
                "description", "First Z-slice, inclusive (default: 0). "
                        + "Negative values count from the end (-1 = last). "
                        + "Use with z_end for a range. "
                        + "Cannot be used together with z_slice."));
        props.put("z_end", Map.of(
                "type", "integer",
                "description", "Last Z-slice, inclusive (default: -1, i.e. last). "
                        + "Negative values count from the end (-1 = last). "
                        + "Use with z_start for a range. "
                        + "Cannot be used together with z_slice."));
        props.put("timepoint", Map.of(
                "type", "integer",
                "description", "Specific timepoint (default: adaptive across all T, "
                        + "starting from timepoint 0). "
                        + "Negative values count from the end (-1 = last). "
                        + "Cannot be used together with t_start/t_end."));
        props.put("t_start", Map.of(
                "type", "integer",
                "description", "First timepoint, inclusive (default: 0). "
                        + "Negative values count from the end (-1 = last). "
                        + "Use with t_end for a range. "
                        + "Cannot be used together with timepoint."));
        props.put("t_end", Map.of(
                "type", "integer",
                "description", "Last timepoint, inclusive (default: -1, i.e. last). "
                        + "Negative values count from the end (-1 = last). "
                        + "Use with t_start for a range. "
                        + "Cannot be used together with timepoint."));
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
                "object", props, List.of("path"), false, null, null);
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
                "type", "array",
                "items", Map.of("type", "integer"),
                "description", "Channel indices to include (default: all)"));
        props.put("z_start", Map.of(
                "type", "integer",
                "description", "First Z-slice, inclusive (default: 0)"));
        props.put("z_end", Map.of(
                "type", "integer",
                "description", "Last Z-slice, inclusive (default: last)"));
        props.put("t_start", Map.of(
                "type", "integer",
                "description", "First timepoint, inclusive (default: 0)"));
        props.put("t_end", Map.of(
                "type", "integer",
                "description", "Last timepoint, inclusive (default: last)"));
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
                "object", props, List.of("path", "output_path"),
                false, null, null);
    }

    // ================================================================
    // Tool handlers
    // ================================================================

    private CallToolResult handleInspectImage(Map<String, Object> args) {
        try {
            var request = InspectImageTool.Request.of(
                    requireString(args, "path"),
                    optInt(args, "series"),
                    optEnum(args, "detail", DetailLevel.class),
                    optDuration(args, "timeout_seconds"),
                    optLong(args, "max_response_bytes"));

            var result = InspectImageTool.execute(
                    request, pathValidator(), readerFactory());

            return switch (result) {
                case ToolResult.Success<ImageMetadata> s ->
                    CallToolResult.builder()
                            .addTextContent(
                                    JsonUtil.toJson(JsonUtil.toMap(s.value())))
                            .build();
                case ToolResult.Failure<ImageMetadata> f ->
                    errorResult(f);
            };
        } catch (IllegalArgumentException e) {
            return errorResult("Invalid argument: " + e.getMessage());
        }
    }

    private CallToolResult handleGetThumbnail(Map<String, Object> args) {
        try {
            var request = GetThumbnailTool.Request.of(
                    requireString(args, "path"),
                    optInt(args, "series"),
                    optEnum(args, "projection", Projection.class),
                    optIntArray(args, "channels"),
                    optInt(args, "timepoint"),
                    optInt(args, "max_size"),
                    optDuration(args, "timeout_seconds"),
                    optLong(args, "max_bytes"));

            var result = GetThumbnailTool.execute(
                    request, pathValidator(), readerFactory());

            return switch (result) {
                case ToolResult.Success<GetThumbnailTool.ThumbnailResult> s -> {
                    var tr = s.value();
                    var image = new McpSchema.ImageContent(
                            null,
                            Base64.getEncoder().encodeToString(tr.png()),
                            "image/png");
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
        } catch (IllegalArgumentException e) {
            return errorResult("Invalid argument: " + e.getMessage());
        }
    }

    private CallToolResult handleGetPlane(Map<String, Object> args) {
        try {
            var request = GetPlaneTool.Request.of(
                    requireString(args, "path"),
                    optInt(args, "series"),
                    optInt(args, "channel"),
                    optInt(args, "z_slice"),
                    optInt(args, "timepoint"),
                    optBool(args, "normalize"),
                    optInt(args, "max_size"),
                    optDuration(args, "timeout_seconds"),
                    optLong(args, "max_bytes"));

            var result = GetPlaneTool.execute(
                    request, pathValidator(), readerFactory());

            return switch (result) {
                case ToolResult.Success<byte[]> s -> {
                    var image = new McpSchema.ImageContent(
                            null,
                            Base64.getEncoder().encodeToString(s.value()),
                            "image/png");
                    yield CallToolResult.builder()
                            .addContent(image)
                            .build();
                }
                case ToolResult.Failure<byte[]> f ->
                    errorResult(f);
            };
        } catch (IllegalArgumentException e) {
            return errorResult("Invalid argument: " + e.getMessage());
        }
    }

    private CallToolResult handleGetIntensityStats(Map<String, Object> args) {
        try {
            // Map the MCP params to the tool's Range-based request.
            // Single-value params (channel, z_slice, timepoint) are shortcuts
            // for a Range of one element.  Range params (channel_start/end,
            // z_start/end, t_start/end) allow specifying inclusive ranges.
            // Using both the single-value and range params for the same
            // dimension is an error.
            Range channels = parseRange(args,
                    "channel", "channel_start", "channel_end");
            Range zRange = parseRange(args,
                    "z_slice", "z_start", "z_end");
            Range tRange = parseRange(args,
                    "timepoint", "t_start", "t_end");

            var request = GetIntensityStatsTool.Request.of(
                    requireString(args, "path"),
                    optInt(args, "series"),
                    channels,
                    zRange,
                    tRange,
                    optInt(args, "histogram_bins"),
                    optDuration(args, "timeout_seconds"),
                    optLong(args, "max_bytes"));

            var result = GetIntensityStatsTool.execute(
                    request, pathValidator(), readerFactory());

            return switch (result) {
                case ToolResult.Success<GetIntensityStatsTool.StatsResult> s ->
                    CallToolResult.builder()
                            .addTextContent(
                                    JsonUtil.toJson(JsonUtil.toMap(s.value())))
                            .build();
                case ToolResult.Failure<GetIntensityStatsTool.StatsResult> f ->
                    errorResult(f);
            };
        } catch (IllegalArgumentException e) {
            return errorResult("Invalid argument: " + e.getMessage());
        }
    }

    private CallToolResult handleExportToTiff(Map<String, Object> args) {
        try {
            var request = ExportToTiffTool.Request.of(
                    requireString(args, "path"),
                    requireString(args, "output_path"),
                    optInt(args, "series"),
                    optIntArray(args, "channels"),
                    optInt(args, "z_start"),
                    optInt(args, "z_end"),
                    optInt(args, "t_start"),
                    optInt(args, "t_end"),
                    optEnum(args, "compression", Compression.class),
                    optEnum(args, "metadata_mode", MetadataMode.class),
                    optDuration(args, "timeout_seconds"),
                    optLong(args, "max_bytes"));

            // Use output path validator for the second path
            var result = ExportToTiffTool.execute(
                    request, pathValidator(), outputPathValidator(),
                    readerFactory(), writerFactory());

            return switch (result) {
                case ToolResult.Success<ExportToTiffTool.ExportResult> s ->
                    CallToolResult.builder()
                            .addTextContent(
                                    JsonUtil.toJson(JsonUtil.toMap(s.value())))
                            .build();
                case ToolResult.Failure<ExportToTiffTool.ExportResult> f ->
                    errorResult(f);
            };
        } catch (IllegalArgumentException e) {
            return errorResult("Invalid argument: " + e.getMessage());
        }
    }

    // ================================================================
    // ToolResult → CallToolResult error mapping
    // ================================================================

    private static <T> CallToolResult errorResult(ToolResult.Failure<T> f) {
        return errorResult("[" + f.kind().name() + "] " + f.message());
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }

    // ================================================================
    // Argument parsing helpers
    // ================================================================

    /**
     * Parse a dimension range from either a single-value parameter or
     * a start/end pair.  Returns null if none are specified.
     *
     * @throws IllegalArgumentException if the single-value and range
     *         params are both specified
     */
    private static Range parseRange(Map<String, Object> args,
                                     String singleKey,
                                     String startKey,
                                     String endKey) {
        var single = optInt(args, singleKey);
        var start = optInt(args, startKey);
        var end = optInt(args, endKey);

        if (single != null && (start != null || end != null)) {
            throw new IllegalArgumentException(
                    "cannot specify both '" + singleKey + "' and '"
                    + startKey + "'/'" + endKey + "'");
        }

        if (single != null) {
            return Range.of(single);
        }
        if (start != null || end != null) {
            // If only one bound is given, default to 0 for missing
            // start and -1 (last element) for missing end.
            int s = start != null ? start : 0;
            int e = end != null ? end : -1;
            return new Range(s, e);
        }
        return null;
    }

    private static String requireString(Map<String, Object> args, String key) {
        var val = args.get(key);
        if (val == null) {
            throw new IllegalArgumentException("missing required parameter: " + key);
        }
        return val.toString();
    }

    private static Integer optInt(Map<String, Object> args, String key) {
        var val = args.get(key);
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    key + ": expected integer, got: " + val);
        }
    }

    private static Long optLong(Map<String, Object> args, String key) {
        var val = args.get(key);
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    key + ": expected integer, got: " + val);
        }
    }

    private static Boolean optBool(Map<String, Object> args, String key) {
        var val = args.get(key);
        if (val == null) return null;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }

    private static Duration optDuration(Map<String, Object> args, String key) {
        var seconds = optInt(args, key);
        return seconds != null ? Duration.ofSeconds(seconds) : null;
    }

    private static int[] optIntArray(Map<String, Object> args, String key) {
        var val = args.get(key);
        if (val == null) return null;
        if (val instanceof List<?> list) {
            int[] result = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                var item = list.get(i);
                if (item instanceof Number n) {
                    result[i] = n.intValue();
                } else {
                    throw new IllegalArgumentException(
                            key + "[" + i + "]: expected integer, got: " + item);
                }
            }
            return result;
        }
        throw new IllegalArgumentException(
                key + ": expected array, got: " + val.getClass().getSimpleName());
    }

    /**
     * Parse an enum value from a string argument (case-insensitive).
     * Handles the mapping between MCP snake_case names and Java enum names.
     */
    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> E optEnum(
            Map<String, Object> args, String key, Class<E> enumType) {
        var val = args.get(key);
        if (val == null) return null;
        var s = val.toString().toUpperCase().replace(" ", "_");
        try {
            return Enum.valueOf(enumType, s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    key + ": unknown value '" + val + "'. Valid values: "
                    + java.util.Arrays.toString(enumType.getEnumConstants()));
        }
    }
}
