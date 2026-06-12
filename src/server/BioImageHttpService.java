package lab.kerrr.mcpbio.bioimageserver;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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
    static final String VERSION = "0.4.0";

    static final int DEFAULT_PORT = 8722;

    private final BioImageService service;
    private int port;
    /** Interface to bind; null means all interfaces (the default — HTTP is the exposed transport). */
    private String bindAddr;
    /** Whether to require the auth token (opt-in; off by default). */
    private boolean requireToken;

    private volatile HttpServer httpServer;
    private volatile LocalEndpoint endpoint;

    private BioImageHttpService(BioImageService service, int port,
                               String bindAddr, boolean requireToken) {
        this.service = service;
        this.port = port;
        this.bindAddr = bindAddr;
        this.requireToken = requireToken;
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
        private String bindAddr;
        private boolean requireToken;

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

        /** Restrict to a specific interface (default: all interfaces). */
        public Builder bind(String bindAddr) {
            this.bindAddr = bindAddr;
            return this;
        }

        /** Require the per-user auth token on the operation endpoints (default false). */
        public Builder requireToken(boolean requireToken) {
            this.requireToken = requireToken;
            return this;
        }

        public BioImageHttpService build() {
            return new BioImageHttpService(service.build(), port, bindAddr, requireToken);
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
            if (portArg != null) this.port = Integer.parseInt(portArg);
            var bindArg = cli.getOptionValue("bind");
            if (bindArg != null) this.bindAddr = bindArg;
            if (cli.hasOption("require-token")) this.requireToken = true;
            start(port);
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
                .desc("TCP port to listen on (default " + DEFAULT_PORT
                        + "); a second local user can pick another port")
                .build());
        options.addOption(Option.builder()
                .longOpt("bind")
                .hasArg()
                .desc("Interface address to bind, e.g. 127.0.0.1 "
                        + "(default: all interfaces)")
                .build());
        options.addOption(Option.builder()
                .longOpt("require-token")
                .desc("Require the auth token on operation endpoints "
                        + "(printed at startup; /health stays open)")
                .build());
        return options;
    }

    /** Bind and start the server on the given port. */
    public void start(int boundPort) throws IOException {
        InetSocketAddress address = (bindAddr == null)
                ? new InetSocketAddress(boundPort)                       // all interfaces
                : new InetSocketAddress(InetAddress.getByName(bindAddr), boundPort);
        var server = HttpServer.create(address, 0);
        this.httpServer = server;
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Operation endpoints (the token filter, if any, guards exactly these).
        var opContexts = new ArrayList<HttpContext>();
        opContexts.add(server.createContext("/inspect_image",
                ex -> handleJson(ex, service::inspectImage, JsonUtil::toMap)));
        opContexts.add(server.createContext("/get_ome_metadata",
                ex -> handleJson(ex, service::getOmeMetadata, JsonUtil::toMap)));
        opContexts.add(server.createContext("/get_intensity_stats",
                ex -> handleJson(ex, service::getIntensityStats, JsonUtil::toMap)));
        opContexts.add(server.createContext("/export_to_tiff",
                ex -> handleJson(ex, service::exportToTiff, JsonUtil::toMap)));
        opContexts.add(server.createContext("/get_plane", this::handleGetPlane));
        opContexts.add(server.createContext("/get_thumbnail", this::handleGetThumbnail));
        // Health/description stays UNAUTHENTICATED so liveness checks work.
        server.createContext("/", this::handleRoot);

        if (requireToken) {
            // HTTP may be reached remotely, where a local descriptor file is
            // unreadable, so print the token to stderr too; also publish the
            // descriptor for same-user local clients.
            endpoint = LocalEndpoint.create(NAME, null, true);
            var filter = new TokenFilter(endpoint);
            for (var ctx : opContexts) ctx.getFilters().add(filter);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            var e = endpoint;
            if (e != null) e.cleanup();
        }));
        server.start();

        String host = (bindAddr == null) ? "0.0.0.0 (all interfaces)" : bindAddr;
        if (requireToken) {
            Path descriptor = endpoint.publish(server.getAddress().getPort());
            System.err.println(NAME + " " + VERSION + " listening on http://" + host
                    + ":" + server.getAddress().getPort());
            System.err.println(NAME + ": auth token: " + endpoint.token());
            System.err.println(NAME + ": descriptor: " + descriptor);
        } else {
            System.err.println(NAME + " " + VERSION + " listening on http://" + host
                    + ":" + server.getAddress().getPort());
        }
    }

    // ---- test support ----

    /** Build over a pre-configured service (used by tests to inject a fake reader). */
    static BioImageHttpService create(BioImageService service, int port,
                                      String bindAddr, boolean requireToken) {
        return new BioImageHttpService(service, port, bindAddr, requireToken);
    }

    /** The actual bound port (useful when started on port 0). */
    int boundPort() {
        return httpServer.getAddress().getPort();
    }

    /** The per-user auth token, or null when not required (test support). */
    String authTokenForTest() {
        var e = endpoint;
        return e == null ? null : e.token();
    }

    /** Stop the server and remove its descriptor (test teardown). */
    void stop() {
        var s = httpServer;
        if (s != null) s.stop(0);
        var e = endpoint;
        if (e != null) e.cleanup();
    }

    public static void main(String[] args) {
        new BioImageHttpService(BioImageService.builder().build(), DEFAULT_PORT, null, false)
                .run(args);
    }

    /**
     * Rejects operation requests whose {@code Authorization}/{@code X-Auth-Token}
     * header does not match the per-user token, with a 401 in the same JSON shape
     * as every other error.  Attached only to the operation contexts — never to
     * {@code /health} — so liveness checks need no credential.
     */
    private static final class TokenFilter extends Filter {
        private final LocalEndpoint endpoint;

        TokenFilter(LocalEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public String description() {
            return "require-token auth";
        }

        @Override
        public void doFilter(HttpExchange ex, Chain chain) throws IOException {
            String presented = ex.getRequestHeaders().getFirst("Authorization");
            if (presented == null) {
                presented = ex.getRequestHeaders().getFirst("X-Auth-Token");
            }
            if (endpoint.verify(presented)) {
                chain.doFilter(ex);
            } else {
                writeError(ex, 401, "access_denied", "missing or invalid auth token");
            }
        }
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
