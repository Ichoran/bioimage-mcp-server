package lab.kerrr.mcpbio.bioimageserver;

import com.google.protobuf.ByteString;

import io.grpc.Server;
// NOTE: interceptor/metadata/status types are the PLAIN core gRPC API
// (io.grpc.*); only the netty *transport* below is shaded.  Do not import
// io.grpc.netty.shaded.* for these — they would be the wrong, internal copies.
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

import lab.kerrr.mcpbio.bioimageserver.grpc.*;
import lab.kerrr.mcpbio.bioimageserver.grpc.Error;  // shadow java.lang.Error

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local gRPC adapter exposing {@link BioImageService} over a single
 * bidirectional stream.
 *
 * <p>Sibling of {@link BioImageMcpServer} / {@link BioImageHttpService} /
 * {@link BioImageSocketService}: all transport, no image logic.  Like the UDS
 * socket adapter it is a <em>session-capable, persistent-connection</em>
 * transport — the whole session (open, ops, close) lives inside one
 * {@code Session} stream whose lifetime IS the connection.  When the stream
 * ends (client gone / cancelled), every handle opened on it is closed and any
 * in-flight deposit is cancelled.
 *
 * <p>The control plane is the protobuf stream; the <em>data</em> plane for
 * deposits is the same client-owned shared-memory region the socket adapter
 * uses (pixels never cross the wire — only the {@link DepositDescriptor}).
 * Inspect/stats results are returned as JSON strings (JsonUtil is the single
 * source of truth for that shape); plane/thumbnail return PNG bytes inline.
 *
 * <p>Bound to the loopback address only (local-only by design); no TLS.
 * Loopback is machine-local but <em>not</em> user-only — any local user can
 * reach a loopback port — so by default the server requires a per-user
 * <b>auth token</b> ({@link LocalEndpoint}) and binds an <b>ephemeral port</b>,
 * publishing {@code {port, token}} to a per-user descriptor file the same user's
 * client reads to connect.  This keeps other users out and lets multiple users
 * run their own instances without colliding on a fixed port.  {@code --insecure}
 * drops the token; {@code --port} pins a fixed port.  Every path still passes the
 * same deny &gt; allow &gt; client-roots access check as on every other transport.
 */
public final class BioImageGrpcService {

    static final String NAME = "bioimage-grpc";
    static final String VERSION = "0.4.0";
    static final int PROTOCOL = 1;
    /** Conventional fixed port (for {@code --port}); not the default. */
    static final int DEFAULT_PORT = 8723;
    /** Default: let the OS pick a free port (multi-user safe). */
    static final int EPHEMERAL_PORT = 0;

    /** gRPC metadata key carrying the auth token ({@code authorization}). */
    private static final Metadata.Key<String> TOKEN_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final BioImageService service;
    private int port;
    private boolean requireToken;
    private String instance;

    /** Streams currently connected (a shutdown is honored only when sole). */
    private final AtomicInteger activeStreams = new AtomicInteger();

    private volatile Server grpcServer;
    private volatile LocalEndpoint endpoint;

    private BioImageGrpcService(BioImageService service, int port,
                               boolean requireToken, String instance) {
        this.service = service;
        this.port = port;
        this.requireToken = requireToken;
        this.instance = instance;
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
        private int port = EPHEMERAL_PORT;
        private boolean requireToken = true;
        private String instance;

        public Builder allow(String path) {
            service.allow(path);
            return this;
        }

        public Builder deny(String path) {
            service.deny(path);
            return this;
        }

        /** Pin a fixed loopback TCP port (default: an ephemeral port). */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** Whether to require the per-user auth token (default true). */
        public Builder requireToken(boolean requireToken) {
            this.requireToken = requireToken;
            return this;
        }

        /** Optional instance label to disambiguate several same-user instances. */
        public Builder instance(String instance) {
            this.instance = instance;
            return this;
        }

        public BioImageGrpcService build() {
            return new BioImageGrpcService(service.build(), port, requireToken, instance);
        }
    }

    // ================================================================
    // Lifecycle
    // ================================================================

    /**
     * Parse {@code --allow}/{@code --deny}/{@code --port}/{@code --instance}/
     * {@code --insecure}, bind, and serve.
     */
    public void run(String[] args) {
        try {
            var cli = new DefaultParser().parse(grpcOptions(), args);
            service.applyAllowDeny(
                    cli.getOptionValues("allow"), cli.getOptionValues("deny"));
            var portArg = cli.getOptionValue("port");
            if (portArg != null) this.port = Integer.parseInt(portArg);
            var inst = cli.getOptionValue("instance");
            if (inst != null) this.instance = inst;
            if (cli.hasOption("insecure")) this.requireToken = false;
        } catch (ParseException | NumberFormatException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
        try {
            start(port);
            grpcServer.awaitTermination();
        } catch (IOException e) {
            System.err.println("ERROR: failed to start gRPC server: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Options grpcOptions() {
        var options = BioImageService.accessControlOptions();
        options.addOption(Option.builder()
                .longOpt("port")
                .hasArg()
                .desc("Pin a fixed loopback TCP port (e.g. " + DEFAULT_PORT
                        + "); default is an ephemeral port published to the "
                        + "per-user descriptor file")
                .build());
        options.addOption(Option.builder()
                .longOpt("instance")
                .hasArg()
                .desc("Label to run several instances for one user "
                        + "(distinct descriptor file)")
                .build());
        options.addOption(Option.builder()
                .longOpt("insecure")
                .desc("Disable the per-user auth token (any local user may "
                        + "connect; NOT recommended)")
                .build());
        return options;
    }

    /** Bind to loopback and start the server (non-blocking). */
    public void start(int boundPort) throws IOException {
        var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), boundPort);
        var builder = NettyServerBuilder.forAddress(address)
                .addService(new SessionService());
        if (requireToken) {
            // Create the token BEFORE binding; publish the descriptor after, when
            // the actual (possibly ephemeral) port is known.
            endpoint = LocalEndpoint.create(NAME, instance, true);
            builder.intercept(new TokenInterceptor(endpoint));
        }
        grpcServer = builder.build().start();
        int actual = grpcServer.getPort();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            var s = grpcServer;
            if (s != null) s.shutdownNow();
            var e = endpoint;
            if (e != null) e.cleanup();
        }));

        if (requireToken) {
            Path descriptor = endpoint.publish(actual);
            System.err.println(NAME + " " + VERSION
                    + " listening on grpc://127.0.0.1:" + actual
                    + "  (descriptor: " + descriptor + ")");
        } else {
            System.err.println("WARN: gRPC auth token disabled (--insecure); "
                    + "any local user can connect");
            System.err.println(NAME + " " + VERSION
                    + " listening on grpc://127.0.0.1:" + actual);
        }
    }

    /**
     * Rejects any call whose {@code authorization} metadata does not match the
     * per-user token.  Implemented with the plain {@code io.grpc.*} API (the
     * netty transport is shaded; these interceptor types are not).
     */
    private static final class TokenInterceptor implements ServerInterceptor {
        private final LocalEndpoint endpoint;

        TokenInterceptor(LocalEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            if (!endpoint.verify(headers.get(TOKEN_KEY))) {
                call.close(Status.UNAUTHENTICATED
                        .withDescription("missing or invalid auth token"), new Metadata());
                return new ServerCall.Listener<>() { };  // no-op
            }
            return next.startCall(call, headers);
        }
    }

    public static void main(String[] args) {
        new BioImageGrpcService(
                BioImageService.builder().build(), EPHEMERAL_PORT, true, null)
                .run(args);
    }

    // ---- test support ----

    /** Build over a pre-configured service (used by tests to inject a fake reader). */
    static BioImageGrpcService create(BioImageService service, int port) {
        return new BioImageGrpcService(service, port, true, null);
    }

    /** Build with the token explicitly enabled/disabled (test support). */
    static BioImageGrpcService create(BioImageService service, int port, boolean requireToken) {
        return new BioImageGrpcService(service, port, requireToken, null);
    }

    /** The actual bound port (useful when started on port 0). */
    int boundPort() {
        return grpcServer.getPort();
    }

    /** The per-user auth token, or null when running insecure (test support). */
    String authTokenForTest() {
        var e = endpoint;
        return e == null ? null : e.token();
    }

    /** Stop the server immediately and remove its descriptor (test teardown). */
    void stop() {
        var s = grpcServer;
        if (s != null) s.shutdownNow();
        var e = endpoint;
        if (e != null) e.cleanup();
    }

    /** The published descriptor file path, or null when insecure (test support). */
    Path descriptorFileForTest() {
        var e = endpoint;
        return e == null ? null : e.descriptorFile();
    }

    /**
     * Begin a clean shutdown: stop the server (which unblocks {@code run}'s
     * awaitTermination) on a separate thread so the {@code shutdown_ok} reply
     * finishes flushing first.
     */
    private void requestShutdown() {
        Thread.ofPlatform().name("grpc-shutdown").start(() -> {
            var s = grpcServer;
            if (s != null) s.shutdown();
        });
    }

    // ================================================================
    // Service / stream
    // ================================================================

    private final class SessionService extends BioImageGrpc.BioImageImplBase {
        @Override
        public StreamObserver<ClientMsg> session(StreamObserver<ServerMsg> resp) {
            return new SessionStream(resp);
        }
    }

    /**
     * One bidirectional stream = one session-owning connection.  gRPC delivers
     * {@code onNext} serially, but a deposit reply is written from a worker
     * thread, so all writes to {@code resp} are serialized under
     * {@code writeLock} and suppressed once the stream is closed.
     */
    private final class SessionStream implements StreamObserver<ClientMsg> {
        private final StreamObserver<ServerMsg> resp;
        private final Object writeLock = new Object();
        private final Set<String> handles = new HashSet<>();
        private final AtomicReference<BioImageService.DepositHandle> current =
                new AtomicReference<>();
        private final AtomicBoolean cleanedUp = new AtomicBoolean(false);
        private boolean responseClosed = false;

        SessionStream(StreamObserver<ServerMsg> resp) {
            this.resp = resp;
            activeStreams.incrementAndGet();
            send(ServerMsg.newBuilder()
                    .setReady(Ready.newBuilder()
                            .setProtocol(PROTOCOL).setService(NAME).setVersion(VERSION))
                    .build());
        }

        @Override
        public void onNext(ClientMsg msg) {
            String id = msg.getId();
            switch (msg.getMsgCase()) {
                case OPEN -> handleOpen(id, msg.getOpen());
                case CLOSE -> handleClose(id, msg.getClose());
                case INSPECT -> handleInspect(id, msg.getInspect());
                case STATS -> handleStats(id, msg.getStats());
                case PLANE -> handlePlane(id, msg.getPlane());
                case THUMBNAIL -> handleThumbnail(id, msg.getThumbnail());
                case DEPOSIT -> handleDeposit(id, msg.getDeposit());
                case OME_METADATA -> handleOmeMetadata(id, msg.getOmeMetadata());
                case SHUTDOWN -> handleShutdown(id);
                case MSG_NOT_SET -> send(error(id, "invalid_argument",
                        "empty client message (no operation set)"));
            }
        }

        @Override
        public void onError(Throwable t) {
            // Client cancelled or the connection broke: just clean up.  The
            // peer is gone, so we do not (and cannot meaningfully) reply.
            cleanup();
        }

        @Override
        public void onCompleted() {
            cleanup();
            synchronized (writeLock) {
                if (!responseClosed) {
                    responseClosed = true;
                    try { resp.onCompleted(); } catch (RuntimeException ignored) { }
                }
            }
        }

        /** Cancel any in-flight deposit and close every session this stream opened. */
        private void cleanup() {
            if (!cleanedUp.compareAndSet(false, true)) return;
            var dep = current.get();
            if (dep != null) dep.cancel();
            // closeSession takes the session lock, so it waits for a cancelled
            // deposit to unwind before closing the reader.
            for (String h : handles) {
                service.closeSession(h);
            }
            activeStreams.decrementAndGet();
        }

        // ---- per-operation handlers ----

        private void handleOpen(String id, OpenSession req) {
            var args = new LinkedHashMap<String, Object>();
            args.put("path", req.getPath());
            if (req.hasSeries()) args.put("series", req.getSeries());
            switch (service.openSession(args)) {
                case ToolResult.Success<SessionInfo> s -> {
                    handles.add(s.value().handle());
                    send(ServerMsg.newBuilder().setId(id)
                            .setSessionOpened(SessionOpened.newBuilder()
                                    .setHandle(s.value().handle())
                                    .setSummaryJson(JsonUtil.toJson(
                                            JsonUtil.toMap(s.value().summary()))))
                            .build());
                }
                case ToolResult.Failure<SessionInfo> f -> send(errorOf(id, f));
            }
        }

        private void handleClose(String id, CloseSession req) {
            String handle = req.getHandle();
            if (handle.isEmpty()) {
                send(error(id, "invalid_argument", "close requires a handle"));
                return;
            }
            switch (service.closeSession(handle)) {
                case ToolResult.Success<Void> s -> {
                    handles.remove(handle);
                    send(ServerMsg.newBuilder().setId(id)
                            .setClosed(Closed.newBuilder().setHandle(handle)).build());
                }
                case ToolResult.Failure<Void> f -> send(errorOf(id, f));
            }
        }

        private void handleInspect(String id, InspectRequest req) {
            var args = new LinkedHashMap<String, Object>();
            putSource(args, req.getPath(), req.getHandle());
            if (req.hasSeries()) args.put("series", req.getSeries());
            if (req.hasDetail()) args.put("detail", req.getDetail());
            switch (service.inspectImage(args)) {
                case ToolResult.Success<ImageMetadata> s ->
                        send(json(id, JsonUtil.toMap(s.value())));
                case ToolResult.Failure<ImageMetadata> f -> send(errorOf(id, f));
            }
        }

        private void handleStats(String id, StatsRequest req) {
            var args = new LinkedHashMap<String, Object>();
            putSource(args, req.getPath(), req.getHandle());
            if (req.hasSeries()) args.put("series", req.getSeries());
            putSlice(args, "channels", req.getChannels());
            putSlice(args, "z", req.getZ());
            putSlice(args, "t", req.getT());
            if (req.hasHistogramBins()) args.put("histogram_bins", req.getHistogramBins());
            switch (service.getIntensityStats(args)) {
                case ToolResult.Success<GetIntensityStatsTool.StatsResult> s ->
                        send(json(id, JsonUtil.toMap(s.value())));
                case ToolResult.Failure<GetIntensityStatsTool.StatsResult> f ->
                        send(errorOf(id, f));
            }
        }

        private void handlePlane(String id, PlaneRequest req) {
            var args = new LinkedHashMap<String, Object>();
            putSource(args, req.getPath(), req.getHandle());
            if (req.hasSeries()) args.put("series", req.getSeries());
            putSlice(args, "channel", req.getChannel());
            putSlice(args, "z", req.getZ());
            putSlice(args, "t", req.getT());
            if (req.hasNormalize()) args.put("normalize", req.getNormalize());
            if (req.hasMaxSize()) args.put("max_size", req.getMaxSize());
            switch (service.getPlane(args)) {
                case ToolResult.Success<byte[]> s -> send(png(id, s.value(), null));
                case ToolResult.Failure<byte[]> f -> send(errorOf(id, f));
            }
        }

        private void handleThumbnail(String id, ThumbnailRequest req) {
            var args = new LinkedHashMap<String, Object>();
            putSource(args, req.getPath(), req.getHandle());
            if (req.hasSeries()) args.put("series", req.getSeries());
            if (req.hasProjection()) args.put("projection", req.getProjection());
            putSlice(args, "channels", req.getChannels());
            putSlice(args, "t", req.getT());
            if (req.hasMaxSize()) args.put("max_size", req.getMaxSize());
            switch (service.getThumbnail(args)) {
                case ToolResult.Success<GetThumbnailTool.ThumbnailResult> s ->
                        send(png(id, s.value().png(),
                                s.value().projectionUsed().name().toLowerCase()));
                case ToolResult.Failure<GetThumbnailTool.ThumbnailResult> f ->
                        send(errorOf(id, f));
            }
        }

        private void handleOmeMetadata(String id, OmeMetadataRequest req) {
            var args = new LinkedHashMap<String, Object>();
            putSource(args, req.getPath(), req.getHandle());
            if (req.hasMaxResponseBytes()) {
                args.put("max_response_bytes", req.getMaxResponseBytes());
            }
            switch (service.getOmeMetadata(args)) {
                case ToolResult.Success<OmeMetadata> s -> send(ServerMsg.newBuilder()
                        .setId(id)
                        .setOmeMetadata(OmeMetadataResult.newBuilder()
                                .setFormat(s.value().format())
                                .setContent(s.value().content()))
                        .build());
                case ToolResult.Failure<OmeMetadata> f -> send(errorOf(id, f));
            }
        }

        private void handleDeposit(String id, DepositRequest req) {
            if (current.get() != null) {
                send(error(id, "invalid_argument",
                        "a deposit is already in flight on this stream"));
                return;
            }
            // Pyramid level: we serve only full resolution.  Refuse any other
            // level rather than silently downgrading to 0 (that would be false
            // confidence — the client would think it got a downsampled tier).
            if (req.getLevel() != 0) {
                send(error(id, "invalid_argument",
                        "this server serves only pyramid level 0 (full resolution); "
                        + "requested level " + req.getLevel()));
                return;
            }
            // The `y`/`x` sub-range fields are accepted for protocol
            // compatibility but ignored: Bio-Formats is plane-based, so the full
            // Y/X plane is always deposited.  We do not translate them into the
            // service args; the Filled descriptor's shape.y/shape.x report the
            // actual extent served, so the client can see it got the whole plane.
            var args = new LinkedHashMap<String, Object>();
            putSource(args, req.getPath(), req.getHandle());
            if (req.hasSeries()) args.put("series", req.getSeries());
            putSlice(args, "channels", req.getChannels());
            putSlice(args, "z", req.getZ());
            putSlice(args, "t", req.getT());
            if (req.hasDryRun()) args.put("dry_run", req.getDryRun());
            if (req.hasTimeoutSeconds()) args.put("timeout_seconds", req.getTimeoutSeconds());
            if (req.hasTarget()) {
                var t = req.getTarget();
                var target = new LinkedHashMap<String, Object>();
                target.put("kind", t.getKind().isEmpty() ? "file" : t.getKind());
                target.put("path", t.getPath());
                target.put("capacity_bytes", t.getCapacityBytes());
                args.put("target", target);
            }
            var dep = service.startDeposit(args);
            current.set(dep);
            // Await + reply on a worker so onNext returns to receiving promptly.
            Thread.ofVirtual().name("grpc-deposit-reply").start(() -> {
                var result = dep.await();
                current.compareAndSet(dep, null);
                if (dep.wasCancelled()) return;  // client went away
                switch (result) {
                    case ToolResult.Success<DepositDescriptor> s -> send(filled(id, s.value()));
                    case ToolResult.Failure<DepositDescriptor> f -> send(errorOf(id, f));
                }
            });
        }

        private void handleShutdown(String id) {
            int others = activeStreams.get() - 1;
            if (others > 0) {
                send(error(id, "shutdown_refused",
                        "refusing shutdown: " + others + " other client(s) connected"));
                return;
            }
            var dep = current.get();
            if (dep != null) dep.cancel();
            send(ServerMsg.newBuilder().setId(id)
                    .setShutdownOk(ShutdownOk.getDefaultInstance()).build());
            onCompleted();
            requestShutdown();
        }

        /** Serialize all writes; suppress once the stream is closed/broken. */
        private void send(ServerMsg msg) {
            synchronized (writeLock) {
                if (responseClosed) return;
                try {
                    resp.onNext(msg);
                } catch (RuntimeException e) {
                    // Client disconnected before we could deliver — safe to drop;
                    // cleanup() handles the session lifecycle.
                    responseClosed = true;
                }
            }
        }
    }

    // ================================================================
    // Message construction helpers
    // ================================================================

    /** Put either handle (session) or path (stateless); the service validates. */
    private static void putSource(Map<String, Object> args, String path, String handle) {
        if (handle != null && !handle.isEmpty()) {
            args.put("handle", handle);
        } else if (path != null && !path.isEmpty()) {
            args.put("path", path);
        }
        // If neither is set, the service reports the missing required parameter.
    }

    /** A blank slice string means "not provided" (the service then errors). */
    private static void putSlice(Map<String, Object> args, String key, String value) {
        if (value != null && !value.isEmpty()) {
            args.put(key, value);
        }
    }

    private static ServerMsg json(String id, Map<String, Object> data) {
        return ServerMsg.newBuilder().setId(id)
                .setJson(JsonResult.newBuilder().setJson(JsonUtil.toJson(data)))
                .build();
    }

    private static ServerMsg png(String id, byte[] bytes, String projectionUsed) {
        var b = PngResult.newBuilder().setPng(ByteString.copyFrom(bytes));
        if (projectionUsed != null) b.setProjectionUsed(projectionUsed);
        return ServerMsg.newBuilder().setId(id).setPng(b).build();
    }

    private static ServerMsg filled(String id, DepositDescriptor d) {
        var fb = Filled.newBuilder()
                .setOffset(d.offset())
                .setTotalBytes(d.totalBytes())
                .setPlaneBytes(d.planeBytes())
                .setPixelType(d.pixelType())
                .setBytesPerSample(d.bytesPerSample())
                .setSigned(d.signed())
                .setLittleEndian(d.littleEndian())
                .addAllAxisOrder(DepositDescriptor.AXIS_ORDER)
                .setSizeX(d.sizeX()).setSizeY(d.sizeY())
                .setSizeC(d.sizeC()).setSizeZ(d.sizeZ()).setSizeT(d.sizeT());
        for (DepositDescriptor.AxisSelection as : d.selection()) {
            var asb = AxisSelection.newBuilder().setAxis(as.axis());
            for (DepositDescriptor.IndexRange r : as.ranges()) {
                asb.addRanges(AxisRange.newBuilder().setStart(r.start()).setStop(r.stop()));
            }
            fb.addSelection(asb);
        }
        return ServerMsg.newBuilder().setId(id).setFilled(fb).build();
    }

    private static ServerMsg errorOf(String id, ToolResult.Failure<?> f) {
        return error(id, f.kind().name().toLowerCase(), f.message());
    }

    private static ServerMsg error(String id, String kind, String message) {
        return ServerMsg.newBuilder().setId(id)
                .setError(Error.newBuilder().setErrorKind(kind).setMessage(message))
                .build();
    }
}
