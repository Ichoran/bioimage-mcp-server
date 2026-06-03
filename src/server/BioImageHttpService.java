package lab.kerrr.mcpbio.bioimageserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Plain-HTTP adapter exposing the {@link BioImageService} as a microservice.
 *
 * <p>This is a sibling of {@link BioImageMcpServer}: both wrap the same
 * protocol-neutral {@link BioImageService} and contain <em>only</em>
 * transport glue.  Where the MCP adapter speaks JSON-RPC over stdio, this
 * one speaks HTTP — so launching it is just a different JBang wrapper.
 *
 * <p><b>Endpoints.</b>  Each operation is a {@code POST} whose body is the
 * same flat snake_case JSON argument object the MCP tools accept:
 * <ul>
 *   <li>{@code POST /inspect_image} → {@code application/json}</li>
 *   <li>{@code POST /get_intensity_stats} → {@code application/json}</li>
 *   <li>{@code POST /export_to_tiff} → {@code application/json}</li>
 *   <li>{@code POST /get_plane} → {@code image/png} (the raw PNG bytes)</li>
 *   <li>{@code POST /get_thumbnail} → {@code image/png}; the chosen
 *       projection is returned in the {@code X-Projection-Used} header</li>
 *   <li>{@code GET /} or {@code GET /health} → service description JSON</li>
 * </ul>
 * The image endpoints return the bytes directly rather than base64 — this
 * adapter <em>serves images</em>, and a future shared-memory deposit sink
 * (mmap on Linux, named mapping on Windows, temp-file fallback) slots in
 * here by returning a descriptor instead of the body.
 *
 * <p><b>Error handling.</b>  Every {@link ToolResult.Failure} maps to an
 * HTTP status by error kind ({@code ACCESS_DENIED}→403,
 * {@code INVALID_ARGUMENT}→400, {@code TIMEOUT}→504, {@code IO_ERROR}→502)
 * with a JSON body {@code {"error_kind","message"}}.  Nothing is silently
 * swallowed: a malformed request becomes a 400 and an unexpected exception
 * becomes a 500 carrying the exception detail.
 *
 * <p><b>Concurrency.</b>  Requests run on a virtual-thread-per-task
 * executor.  Each operation obtains its own Bio-Formats reader from the
 * service's factory, so concurrent requests never share a (non-thread-safe)
 * reader.
 *
 * @see BioImageService
 */
public final class BioImageHttpService {

    static final String NAME = "bioimage-http";
    static final String VERSION = "0.2.0";

    static final int DEFAULT_PORT = 8722;

    private final BioImageService service;
    private final int port;

    private BioImageHttpService(BioImageService service, int port) {
        this.service = service;
        this.port = port;
    }

    // ================================================================
    // Builder
    // ================================================================

    public static Builder builder() {
        return new Builder();
    }

    /** Thin builder delegating path rules to {@link BioImageService.Builder}. */
    public static class Builder {
        private final BioImageService.Builder service = BioImageService.builder();
        private int port = DEFAULT_PORT;

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

        /** Set the TCP port to listen on (default {@value #DEFAULT_PORT}). */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public BioImageHttpService build() {
            return new BioImageHttpService(service.build(), port);
        }
    }

    // ================================================================
    // Lifecycle
    // ================================================================

    /**
     * Parse CLI options ({@code --allow}, {@code --deny}, {@code --port}),
     * bind the HTTP server, and start serving.  Blocks the calling thread
     * until the JVM is shut down.
     */
    public void run(String[] args) {
        try {
            var cli = new DefaultParser().parse(httpOptions(), args);
            service.applyAllowDeny(
                    cli.getOptionValues("allow"), cli.getOptionValues("deny"));
            var portArg = cli.getOptionValue("port");
            int boundPort = portArg != null ? Integer.parseInt(portArg) : port;
            start(boundPort);
        } catch (ParseException | NumberFormatException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("ERROR: failed to start HTTP server: "
                    + e.getMessage());
            System.exit(1);
        }
    }

    private static Options httpOptions() {
        var options = BioImageService.accessControlOptions();
        options.addOption(Option.builder()
                .longOpt("port")
                .hasArg()
                .desc("TCP port to listen on (default " + DEFAULT_PORT + ")")
                .build());
        return options;
    }

    /** Bind and start the server on the given port. */
    public void start(int boundPort) throws IOException {
        var server = HttpServer.create(new InetSocketAddress(boundPort), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/inspect_image",
                ex -> handleJson(ex, service::inspectImage, JsonUtil::toMap));
        server.createContext("/get_ome_metadata",
                ex -> handleJson(ex, service::getOmeMetadata, JsonUtil::toMap));
        server.createContext("/get_intensity_stats",
                ex -> handleJson(ex, service::getIntensityStats, JsonUtil::toMap));
        server.createContext("/export_to_tiff",
                ex -> handleJson(ex, service::exportToTiff, JsonUtil::toMap));
        server.createContext("/get_plane", this::handleGetPlane);
        server.createContext("/get_thumbnail", this::handleGetThumbnail);
        server.createContext("/", this::handleRoot);

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> server.stop(0)));
        server.start();
        System.err.println(NAME + " " + VERSION
                + " listening on http://127.0.0.1:" + boundPort);
    }

    public static void main(String[] args) {
        new BioImageHttpService(BioImageService.builder().build(), DEFAULT_PORT)
                .run(args);
    }

    // ================================================================
    // Request handling
    // ================================================================

    /** A service operation: argument map → structured result. */
    @FunctionalInterface
    private interface Operation<T> {
        ToolResult<T> apply(Map<String, Object> args);
    }

    /** Renders a successful value to a JSON-ready map. */
    @FunctionalInterface
    private interface JsonRenderer<T> {
        Map<String, Object> render(T value);
    }

    /**
     * Handle a JSON-in/JSON-out endpoint.  Reads the request body as the
     * argument map, runs the operation, and writes the rendered value (or
     * a structured error) as JSON.
     */
    private <T> void handleJson(HttpExchange ex, Operation<T> op,
                                JsonRenderer<T> renderer) throws IOException {
        Map<String, Object> args = readArgs(ex);
        if (args == null) return;  // already responded with 400

        switch (op.apply(args)) {
            case ToolResult.Success<T> s ->
                writeJson(ex, 200, renderer.render(s.value()));
            case ToolResult.Failure<T> f -> writeError(ex, f);
        }
    }

    private void handleGetPlane(HttpExchange ex) throws IOException {
        Map<String, Object> args = readArgs(ex);
        if (args == null) return;

        switch (service.getPlane(args)) {
            case ToolResult.Success<byte[]> s -> writePng(ex, s.value());
            case ToolResult.Failure<byte[]> f -> writeError(ex, f);
        }
    }

    private void handleGetThumbnail(HttpExchange ex) throws IOException {
        Map<String, Object> args = readArgs(ex);
        if (args == null) return;

        switch (service.getThumbnail(args)) {
            case ToolResult.Success<GetThumbnailTool.ThumbnailResult> s -> {
                var tr = s.value();
                ex.getResponseHeaders().add("X-Projection-Used",
                        tr.projectionUsed().name().toLowerCase());
                writePng(ex, tr.png());
            }
            case ToolResult.Failure<GetThumbnailTool.ThumbnailResult> f ->
                writeError(ex, f);
        }
    }

    /** Simple service description / health check. */
    private void handleRoot(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            writeError(ex, 405, "method_not_allowed",
                    "use GET for / and POST for the operation endpoints");
            return;
        }
        var info = new LinkedHashMap<String, Object>();
        info.put("service", NAME);
        info.put("version", VERSION);
        info.put("status", "ok");
        info.put("endpoints", List.of(
                "POST /inspect_image", "POST /get_ome_metadata",
                "POST /get_thumbnail", "POST /get_plane",
                "POST /get_intensity_stats", "POST /export_to_tiff"));
        writeJson(ex, 200, info);
    }

    // ================================================================
    // Request/response plumbing
    // ================================================================

    /**
     * Require POST and parse the JSON body into an argument map.  On any
     * problem, writes a 4xx response and returns null so the caller stops.
     */
    private Map<String, Object> readArgs(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            writeError(ex, 405, "method_not_allowed",
                    "this endpoint requires POST with a JSON body");
            return null;
        }
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            return JsonUtil.parseObject(body);
        } catch (IllegalArgumentException e) {
            writeError(ex, 400, "invalid_argument", e.getMessage());
            return null;
        }
    }

    private static void writeJson(HttpExchange ex, int status,
                                  Map<String, Object> body) throws IOException {
        byte[] bytes = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void writePng(HttpExchange ex, byte[] png)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.sendResponseHeaders(200, png.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(png);
        }
    }

    /** Map a structured failure to an HTTP status + JSON error body. */
    private static <T> void writeError(HttpExchange ex,
                                       ToolResult.Failure<T> f) throws IOException {
        int status = switch (f.kind()) {
            case ACCESS_DENIED -> 403;
            case INVALID_ARGUMENT -> 400;
            case TIMEOUT -> 504;
            case IO_ERROR -> 502;
        };
        writeError(ex, status, f.kind().name().toLowerCase(), f.message());
    }

    private static void writeError(HttpExchange ex, int status,
                                   String kind, String message)
            throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("error_kind", kind);
        body.put("message", message);
        writeJson(ex, status, body);
    }
}
