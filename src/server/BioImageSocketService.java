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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unix-domain-socket adapter exposing {@link BioImageService}'s shared-memory
 * deposit as a persistent-connection microservice.
 *
 * <p>Sibling of {@link BioImageMcpServer} and {@link BioImageHttpService}:
 * all transport, no image logic.  The control plane is newline-delimited
 * JSON over the socket; the <em>data</em> plane is the client-owned region
 * that {@link BioImageService#deposit} fills — pixels never travel over the
 * socket.  See DESIGN.md §9 for the full protocol.
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
    static final String VERSION = "0.2.0";
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
