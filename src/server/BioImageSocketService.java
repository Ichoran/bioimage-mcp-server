package lab.kerrr.mcpbio.bioimageserver;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unix-domain-socket adapter exposing {@link BioImageService} as a
 * session-capable, persistent-connection microservice.
 *
 * <p>Sibling of {@link BioImageMcpServer}, {@link BioImageHttpService}, and
 * {@link BioImageGrpcService}: all transport, no image logic.  The control
 * plane is newline-delimited JSON over the socket; the <em>data</em> plane for
 * a {@code deposit} is the client-owned region that
 * {@link BioImageService#deposit} fills — pixels never travel over the socket.
 * See DESIGN.md §9 (deposit) and §10 (sessions) for the full protocol.
 *
 * <p>Beyond deposit, this transport serves the read operations
 * ({@code inspect}, {@code get_plane}, {@code get_intensity_stats},
 * {@code get_thumbnail}) and the session lifecycle ({@code open}/{@code close}):
 * a client may {@code open} an image once and address later ops by the returned
 * {@code handle} instead of a {@code path}, reusing one kept-open reader.  Those
 * ops run synchronously on the read loop (each bounded by its own budget); only
 * {@code deposit} runs asynchronously so the loop stays free to notice a
 * disconnect mid-fill.  When the connection drops, every handle it opened is
 * closed (its reader released).  PNG results (plane/thumbnail) are returned as
 * base64 in the reply line — a convenience path; raw pixel volumes should use
 * {@code deposit} into shared memory.
 *
 * <p><b>Per connection (one deposit in flight, sequential):</b>
 * <pre>
 *   server → {"type":"ready","protocol":1,...}                 (hello)
 *   client → {"type":"deposit","id":"c1", &lt;selection&gt;, "target":{...}}
 *   server → {"type":"filled","id":"c1", &lt;descriptor&gt;}    (== ready signal)
 *        or → {"type":"error","id":"c1","error_kind":"...","message":"..."}
 * </pre>
 * The connection thread does nothing but read: when it sees EOF (client
 * closed) it cancels any in-flight deposit, so a dropped connection promptly
 * aborts the fill and the region is left untouched (never unlinked).  The
 * reply for a deposit is written from a worker thread once the fill
 * finishes, so the read loop is always free to notice a disconnect.
 *
 * <p>A {@code dry_run:true} deposit returns the descriptor (with
 * {@code total_bytes}) without a target, so the client can size the region
 * before the real call.
 */
public final class BioImageSocketService {

    static final String NAME = "bioimage-socket";
    static final String VERSION = "0.3.1";
    static final int PROTOCOL = 1;

    private final BioImageService service;
    private final Path socketPath;

    /** Number of client connections currently being served. */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /** The bound listener, so a shutdown request can close it. */
    private volatile ServerSocketChannel serverChannel;

    private BioImageSocketService(BioImageService service, Path socketPath) {
        this.service = service;
        this.socketPath = socketPath;
    }

    /** Default socket path: under XDG_RUNTIME_DIR if set, else the temp dir. */
    static Path defaultSocketPath() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        String dir = (runtime != null && !runtime.isBlank())
                ? runtime
                : System.getProperty("java.io.tmpdir", "/tmp");
        return Path.of(dir, "bioimage-deposit.sock");
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
        private Path socketPath = defaultSocketPath();

        public Builder allow(String path) {
            service.allow(path);
            return this;
        }

        public Builder deny(String path) {
            service.deny(path);
            return this;
        }

        /** Set the Unix-domain socket path to bind. */
        public Builder socket(String path) {
            this.socketPath = Path.of(path);
            return this;
        }

        public BioImageSocketService build() {
            return new BioImageSocketService(service.build(), socketPath);
        }
    }

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Parse {@code --allow}/{@code --deny}/{@code --socket}, bind, and serve. */
    public void run(String[] args) {
        Path bindPath = socketPath;
        try {
            var cli = new DefaultParser().parse(socketOptions(), args);
            service.applyAllowDeny(
                    cli.getOptionValues("allow"), cli.getOptionValues("deny"));
            var sock = cli.getOptionValue("socket");
            if (sock != null) {
                bindPath = Path.of(sock);
            }
        } catch (ParseException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
        try {
            serve(bindPath);
        } catch (IOException e) {
            System.err.println("ERROR: failed to start socket server: "
                    + e.getMessage());
            System.exit(1);
        }
    }

    private static Options socketOptions() {
        var options = BioImageService.accessControlOptions();
        options.addOption(Option.builder()
                .longOpt("socket")
                .hasArg()
                .desc("Unix-domain socket path to bind "
                        + "(default " + defaultSocketPath() + ")")
                .build());
        return options;
    }

    /** Bind the socket and run the accept loop (blocks the calling thread). */
    public void serve(Path bindPath) throws IOException {
        // Remove a stale socket file from a previous run.  The server owns
        // this file (it is not user data), so removing it is safe.
        Files.deleteIfExists(bindPath);

        var address = UnixDomainSocketAddress.of(bindPath);
        var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(address);
        this.serverChannel = server;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.close(); } catch (IOException ignored) { /* shutting down */ }
            try { Files.deleteIfExists(bindPath); } catch (IOException ignored) { }
        }));

        System.err.println(NAME + " " + VERSION + " listening on " + bindPath);

        while (server.isOpen()) {
            SocketChannel channel;
            try {
                channel = server.accept();
            } catch (IOException e) {
                if (!server.isOpen()) break;  // closed during shutdown
                System.err.println("WARN: accept failed: " + e.getMessage());
                continue;
            }
            Thread.ofVirtual().name("deposit-conn").start(() -> handle(channel));
        }
    }

    public static void main(String[] args) {
        new BioImageSocketService(
                BioImageService.builder().build(), defaultSocketPath())
                .run(args);
    }

    /** Build over a pre-configured service (used by tests to inject a fake reader). */
    static BioImageSocketService create(BioImageService service, Path socketPath) {
        return new BioImageSocketService(service, socketPath);
    }

    /** Open session count of the underlying service (test diagnostics). */
    int openSessionCount() {
        return service.openSessionCount();
    }

    // ================================================================
    // Connection handling
    // ================================================================

    private void handle(SocketChannel channel) {
        // The connection thread only ever reads (so it can detect EOF
        // promptly).  Replies are written from worker threads; a single
        // lock serializes all writes to the channel.
        activeConnections.incrementAndGet();
        var writeLock = new Object();
        var current = new AtomicReference<BioImageService.DepositHandle>();
        // Sessions opened on this connection; closed when it goes away.
        var handles = new HashSet<String>();
        try (channel;
             var in = new BufferedInputStream(Channels.newInputStream(channel))) {
            OutputStream out = Channels.newOutputStream(channel);
            sendLine(out, writeLock, hello());

            while (true) {
                String line = readLine(in);
                if (line == null) {
                    // Client closed: cancel any in-flight deposit and stop.
                    var dep = current.get();
                    if (dep != null) dep.cancel();
                    break;
                }
                if (line.isBlank()) continue;

                Map<String, Object> msg;
                try {
                    msg = JsonUtil.parseObject(line);
                } catch (IllegalArgumentException e) {
                    sendLine(out, writeLock,
                            error(null, "invalid_argument", e.getMessage()));
                    continue;
                }

                String type = asString(msg.get("type"));
                final String id = asString(msg.get("id"));

                if ("shutdown".equals(type)) {
                    // Honor shutdown only when this requester is the sole
                    // connected client, so one client can never tear the
                    // server out from under others.
                    int others = activeConnections.get() - 1;
                    if (others > 0) {
                        sendLine(out, writeLock, error(id, "shutdown_refused",
                                "refusing shutdown: " + others + " other client(s) "
                                + "connected"));
                        continue;
                    }
                    var dep = current.get();
                    if (dep != null) dep.cancel();
                    sendLine(out, writeLock, shutdownOk(id));
                    requestShutdown();
                    break;
                }

                // Synchronous session + read operations.  These run inline on
                // the connection thread (each is bounded by its own internal
                // time/byte budget); only the long-running deposit is async so
                // the read loop stays free to notice a disconnect during a fill.
                if (isSyncOp(type)) {
                    sendLine(out, writeLock, dispatchSync(type, id, msg, handles));
                    continue;
                }

                if (!"deposit".equals(type)) {
                    sendLine(out, writeLock, error(id, "invalid_argument",
                            "unknown message type: " + type));
                    continue;
                }

                if (current.get() != null) {
                    sendLine(out, writeLock, error(id, "invalid_argument",
                            "a deposit is already in flight on this connection"));
                    continue;
                }

                var dep = service.startDeposit(msg);
                current.set(dep);
                // Await + reply on a worker so this loop returns to reading.
                Thread.ofVirtual().name("deposit-reply").start(() -> {
                    var result = dep.await();
                    // Mark this connection free for the next deposit *before*
                    // replying, so a sequential client's next request (sent
                    // after it receives the reply) is never spuriously
                    // rejected as "already in flight".
                    current.compareAndSet(dep, null);
                    if (dep.wasCancelled()) {
                        return;  // client went away — nothing to deliver
                    }
                    try {
                        sendLine(out, writeLock, switch (result) {
                            case ToolResult.Success<DepositDescriptor> s ->
                                    filled(id, s.value());
                            case ToolResult.Failure<DepositDescriptor> f ->
                                    error(id, f.kind().name().toLowerCase(),
                                            f.message());
                        });
                    } catch (IOException e) {
                        // Client disconnected before we could reply.  The fill
                        // already finished and the sink is closed; there is
                        // nothing to deliver, so this is safe to drop.
                    }
                });
            }
        } catch (IOException e) {
            // Connection-level I/O error: cancel any in-flight work and exit.
            var dep = current.get();
            if (dep != null) dep.cancel();
        } finally {
            // The connection is going away: drop every session it opened,
            // closing the kept-open reader.  Any in-flight deposit was already
            // cancelled on the EOF/error path; closeSession takes the session
            // lock, so it waits for that fill to unwind before closing.
            for (String h : handles) {
                service.closeSession(h);
            }
            activeConnections.decrementAndGet();
        }
    }

    /**
     * Begin a clean shutdown: close the listener so {@link #serve} returns,
     * then exit.  The shutdown hook removes the socket file.  Runs the exit
     * on a separate thread so the {@code shutdown_ok} reply finishes flushing
     * and the requesting handler can return first.
     */
    private void requestShutdown() {
        var server = serverChannel;
        if (server != null) {
            try { server.close(); }
            catch (IOException ignored) { /* already shutting down */ }
        }
        Thread.ofPlatform().name("deposit-shutdown").start(() -> System.exit(0));
    }

    // ================================================================
    // Message construction
    // ================================================================

    private static String hello() {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", "ready");
        map.put("protocol", PROTOCOL);
        map.put("service", NAME);
        map.put("version", VERSION);
        return JsonUtil.toJson(map);
    }

    private static String filled(String id, DepositDescriptor d) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", "filled");
        if (id != null) map.put("id", id);
        map.putAll(JsonUtil.toMap(d));
        return JsonUtil.toJson(map);
    }

    private static String error(String id, String kind, String message) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", "error");
        if (id != null) map.put("id", id);
        map.put("error_kind", kind);
        map.put("message", message);
        return JsonUtil.toJson(map);
    }

    private static String shutdownOk(String id) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", "shutdown_ok");
        if (id != null) map.put("id", id);
        return JsonUtil.toJson(map);
    }

    // ================================================================
    // Session + read operations (synchronous, inline on the read loop)
    // ================================================================

    /** Message types handled synchronously (everything but deposit/shutdown). */
    private static boolean isSyncOp(String type) {
        return switch (type == null ? "" : type) {
            case "open", "close", "inspect", "get_intensity_stats",
                 "get_plane", "get_thumbnail", "get_ome_metadata" -> true;
            default -> false;
        };
    }

    /**
     * Run a synchronous operation and build its reply line.  Read ops accept
     * either {@code path} (stateless) or {@code handle} (session) in {@code msg}
     * — the service decides; this adapter is pure transport.  {@code open}
     * records the new handle so the connection's {@code finally} can close it,
     * and {@code close} removes it.
     */
    private String dispatchSync(String type, String id, Map<String, Object> msg,
                                Set<String> handles) {
        switch (type) {
            case "open":
                return switch (service.openSession(msg)) {
                    case ToolResult.Success<SessionInfo> s -> {
                        handles.add(s.value().handle());
                        yield sessionOpened(id, s.value());
                    }
                    case ToolResult.Failure<SessionInfo> f -> errorOf(id, f);
                };
            case "close": {
                String handle = asString(msg.get("handle"));
                if (handle == null) {
                    return error(id, "invalid_argument", "close requires a handle");
                }
                return switch (service.closeSession(handle)) {
                    case ToolResult.Success<Void> s -> {
                        handles.remove(handle);
                        yield closed(id, handle);
                    }
                    case ToolResult.Failure<Void> f -> errorOf(id, f);
                };
            }
            case "inspect":
                return switch (service.inspectImage(msg)) {
                    case ToolResult.Success<ImageMetadata> s ->
                            jsonReply("inspected", id, JsonUtil.toMap(s.value()));
                    case ToolResult.Failure<ImageMetadata> f -> errorOf(id, f);
                };
            case "get_intensity_stats":
                return switch (service.getIntensityStats(msg)) {
                    case ToolResult.Success<GetIntensityStatsTool.StatsResult> s ->
                            jsonReply("stats", id, JsonUtil.toMap(s.value()));
                    case ToolResult.Failure<GetIntensityStatsTool.StatsResult> f ->
                            errorOf(id, f);
                };
            case "get_plane":
                return switch (service.getPlane(msg)) {
                    case ToolResult.Success<byte[]> s -> pngReply("plane", id, s.value(), null);
                    case ToolResult.Failure<byte[]> f -> errorOf(id, f);
                };
            case "get_thumbnail":
                return switch (service.getThumbnail(msg)) {
                    case ToolResult.Success<GetThumbnailTool.ThumbnailResult> s ->
                            pngReply("thumbnail", id, s.value().png(),
                                    s.value().projectionUsed().name().toLowerCase());
                    case ToolResult.Failure<GetThumbnailTool.ThumbnailResult> f ->
                            errorOf(id, f);
                };
            case "get_ome_metadata":
                return switch (service.getOmeMetadata(msg)) {
                    case ToolResult.Success<OmeMetadata> s -> omeMetadata(id, s.value());
                    case ToolResult.Failure<OmeMetadata> f -> errorOf(id, f);
                };
            default:
                return error(id, "invalid_argument", "unsupported sync op: " + type);
        }
    }

    private static String errorOf(String id, ToolResult.Failure<?> f) {
        return error(id, f.kind().name().toLowerCase(), f.message());
    }

    private static String sessionOpened(String id, SessionInfo info) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", "session_opened");
        if (id != null) map.put("id", id);
        map.putAll(JsonUtil.toMap(info));   // handle + summary
        return JsonUtil.toJson(map);
    }

    private static String omeMetadata(String id, OmeMetadata m) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", "ome_metadata");
        if (id != null) map.put("id", id);
        map.putAll(JsonUtil.toMap(m));   // format + content
        return JsonUtil.toJson(map);
    }

    private static String closed(String id, String handle) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", "closed");
        if (id != null) map.put("id", id);
        map.put("handle", handle);
        return JsonUtil.toJson(map);
    }

    /** JSON-result reply (inspect/stats): the value goes under {@code result}. */
    private static String jsonReply(String type, String id, Map<String, Object> data) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type);
        if (id != null) map.put("id", id);
        map.put("result", data);
        return JsonUtil.toJson(map);
    }

    /**
     * PNG reply (plane/thumbnail): the rendered image is base64 in
     * {@code png_base64}.  Convenience path — raw pixel volumes should use
     * {@code deposit} into a shared-memory region instead.
     */
    private static String pngReply(String type, String id, byte[] png,
                                   String projectionUsed) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type);
        if (id != null) map.put("id", id);
        if (projectionUsed != null) map.put("projection_used", projectionUsed);
        map.put("png_base64", Base64.getEncoder().encodeToString(png));
        return JsonUtil.toJson(map);
    }

    // ================================================================
    // Framing
    // ================================================================

    /** Write one JSON message followed by a newline, flushed, under a lock. */
    private static void sendLine(OutputStream out, Object writeLock, String json)
            throws IOException {
        // JSON is single-line (no embedded newlines from our serializer once
        // compacted); collapse any indentation newlines defensively.
        String oneLine = json.replace("\n", " ");
        byte[] bytes = (oneLine + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            out.write(bytes);
            out.flush();
        }
    }

    /**
     * Read one newline-terminated line as UTF-8, or null at end of stream.
     * A trailing carriage return is stripped.
     */
    private static String readLine(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return decodeLine(buffer);
            }
            buffer.write(b);
        }
        // EOF: deliver a trailing partial line if any bytes were read.
        return buffer.size() > 0 ? decodeLine(buffer) : null;
    }

    private static String decodeLine(ByteArrayOutputStream buffer) {
        var s = buffer.toString(StandardCharsets.UTF_8);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
