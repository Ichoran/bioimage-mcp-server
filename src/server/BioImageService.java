package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.Compression;
import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.MetadataMode;
import lab.kerrr.mcpbio.bioimageserver.GetThumbnailTool.Projection;
import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Protocol-neutral core of the BioImage server.
 *
 * <p>This class owns everything that has nothing to do with <em>how</em> a
 * request arrives: file-access policy (deny/allow/client-roots), the
 * Bio-Formats reader/writer factories, and the five image operations
 * themselves.  Each operation accepts a flat {@code Map<String, Object>}
 * of snake_case arguments (the lowest common denominator that an MCP tool
 * call, an HTTP JSON body, or any other transport can produce) and returns
 * a {@link ToolResult} — never throwing, with every error surfaced as a
 * structured {@link ToolResult.Failure}.
 *
 * <p>Adapters wrap this core and translate between their wire format and
 * these methods:
 * <ul>
 *   <li>{@link BioImageMcpServer} — MCP/stdio adapter (the original surface)</li>
 *   <li>{@link BioImageHttpService} — plain HTTP adapter that serves images
 *       and JSON directly</li>
 * </ul>
 * Adding another transport is a matter of writing a new adapter against
 * this class; the image-serving logic is shared verbatim.
 *
 * <p><b>Thread-safety.</b>  The operation methods are safe to call
 * concurrently: each obtains its own {@link ImageReader}/{@link ImageWriter}
 * from the factories (Bio-Formats readers are not thread-safe, so they are
 * never shared), and the access-control snapshot is held in a
 * {@code volatile} field updated atomically.  {@link #setClientRoots} and
 * {@link #applyCliArgs} mutate policy and are expected to be called from a
 * single controlling thread (e.g. an MCP roots callback), but a concurrent
 * operation will simply see either the old or the new policy, never a
 * torn one.
 *
 * @see PathAccessControl
 */
public final class BioImageService {

    /** Deny/allow lists — immutable snapshots, replaced when CLI args are parsed. */
    private volatile List<Path> denyList;
    private volatile List<Path> allowList;

    /** Client roots, updated when an MCP client sends a roots notification. */
    private final CopyOnWriteArrayList<Path> clientRoots = new CopyOnWriteArrayList<>();

    /** The current path access control, rebuilt when policy changes. */
    private volatile PathAccessControl accessControl;

    private final Supplier<ImageReader> readerFactory;
    private final Supplier<ImageWriter> writerFactory;
    private final Supplier<ImageWriter> zarrWriterFactory;

    /**
     * Server-wide cap on OME-Zarr writer threads (from {@code --parallelism}).
     * The pool is shared across all clients/transports, so total compression
     * never exceeds this regardless of client count.  Set before the first
     * export; the pool is sized once, lazily.
     */
    private volatile int parallelism;
    private volatile BlockPool blockPool;

    /**
     * Open sessions, keyed by handle.  Populated by {@link #openSession} and
     * drained by {@link #closeSession} (driven by a session-capable transport
     * when the client closes a handle or the connection drops).  Stateless
     * transports (MCP/stdio, HTTP) never touch this map.
     */
    private final ConcurrentHashMap<String, ImageSession> sessions =
            new ConcurrentHashMap<>();

    private BioImageService(List<Path> denyList, List<Path> allowList,
                            Supplier<ImageReader> readerFactory,
                            Supplier<ImageWriter> writerFactory,
                            Supplier<ImageWriter> zarrWriterFactory,
                            int parallelism) {
        this.denyList = List.copyOf(denyList);
        this.allowList = List.copyOf(allowList);
        this.readerFactory = readerFactory;
        this.writerFactory = writerFactory;
        this.zarrWriterFactory = zarrWriterFactory;
        this.parallelism = parallelism;
        rebuildAccessControl();
    }

    /** Default worker count: ~1/3 of the (cgroup-aware) available cores. */
    static int defaultParallelism() {
        return resolveParallelism("0.334",
                Runtime.getRuntime().availableProcessors());
    }

    /**
     * Resolve a {@code --parallelism} spec to a thread count: an integer is the
     * count directly; a decimal is that fraction of {@code cores}, rounded up
     * (so {@code 0.334} on 12 cores → 5).  Result is at least 1.
     */
    static int resolveParallelism(String spec, int cores) {
        String s = spec.trim();
        if (s.indexOf('.') >= 0) {
            double f = Double.parseDouble(s);
            if (f <= 0) {
                throw new IllegalArgumentException(
                        "parallelism fraction must be > 0: " + spec);
            }
            return Math.max(1, (int) Math.ceil(f * cores));
        }
        int n = Integer.parseInt(s);
        if (n < 1) {
            throw new IllegalArgumentException(
                    "parallelism must be >= 1: " + spec);
        }
        return n;
    }

    /** The resolved server-wide writer-thread count. */
    public int parallelism() {
        return parallelism;
    }

    /** The shared writer pool, created lazily and sized to {@link #parallelism}. */
    private BlockPool blockPool() {
        BlockPool p = blockPool;
        if (p == null) {
            synchronized (this) {
                p = blockPool;
                if (p == null) {
                    p = new BlockPool(parallelism);
                    blockPool = p;
                }
            }
        }
        return p;
    }

    // ================================================================
    // Builder
    // ================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Path> denyList = new ArrayList<>();
        private final List<Path> allowList = new ArrayList<>();
        private Supplier<ImageReader> readerFactory = BioFormatsReader::new;
        private Supplier<ImageWriter> writerFactory = BioFormatsWriter::new;
        private Supplier<ImageWriter> zarrWriterFactory = ZarrWriter::new;
        private int parallelism = defaultParallelism();

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

        /**
         * Override the reader factory (e.g. for testing with a fake reader).
         * Each operation calls the factory to obtain a fresh, single-use
         * reader, so the supplier must produce a new instance per call.
         */
        public Builder readerFactory(Supplier<ImageReader> readerFactory) {
            this.readerFactory = readerFactory;
            return this;
        }

        /** Override the writer factory (e.g. for testing with a fake writer). */
        public Builder writerFactory(Supplier<ImageWriter> writerFactory) {
            this.writerFactory = writerFactory;
            return this;
        }

        /** Override the OME-Zarr writer factory (used by {@code export_to_ngff}). */
        public Builder zarrWriterFactory(Supplier<ImageWriter> zarrWriterFactory) {
            this.zarrWriterFactory = zarrWriterFactory;
            return this;
        }

        /** Server-wide OME-Zarr writer-thread count (see {@code --parallelism}). */
        public Builder parallelism(int parallelism) {
            this.parallelism = Math.max(1, parallelism);
            return this;
        }

        public BioImageService build() {
            return new BioImageService(denyList, allowList,
                    readerFactory, writerFactory, zarrWriterFactory, parallelism);
        }
    }

    // ================================================================
    // Command-line options (shared by all adapters)
    // ================================================================

    /**
     * Returns the {@code --allow}/{@code --deny} options common to every
     * adapter.  Adapters that accept additional options (e.g. an HTTP
     * {@code --port}) can add to this set.
     */
    public static Options accessControlOptions() {
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
        options.addOption(Option.builder()
                .longOpt("parallelism")
                .hasArg()
                .desc("OME-Zarr writer threads: an integer count, or a decimal "
                        + "fraction of available cores (default 0.334). "
                        + "Server-wide, shared across all clients.")
                .build());
        return options;
    }

    /**
     * Merge {@code --allow}/{@code --deny} paths parsed from the command
     * line into the current policy.  Builder-configured paths are kept;
     * CLI paths are appended.  Unknown options are rejected.
     *
     * @throws ParseException if the arguments cannot be parsed
     */
    public void applyCliArgs(String[] args) throws ParseException {
        var cli = new DefaultParser().parse(accessControlOptions(), args);
        applyAllowDeny(cli.getOptionValues("allow"), cli.getOptionValues("deny"));
        String par = cli.getOptionValue("parallelism");
        if (par != null) {
            try {
                this.parallelism = resolveParallelism(
                        par, Runtime.getRuntime().availableProcessors());
            } catch (IllegalArgumentException e) {
                throw new ParseException(
                        "invalid --parallelism: " + e.getMessage());
            }
        }
    }

    /**
     * Merge the given allow/deny paths into the current policy.  Either
     * array may be null (meaning "none").  Useful for adapters that parse
     * a superset of options themselves and want to forward just the
     * access-control ones.
     */
    public void applyAllowDeny(String[] extraAllow, String[] extraDeny) {
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
    }

    /** Return a new immutable list with one path appended. */
    private static List<Path> appendPath(List<Path> existing, String path) {
        var merged = new ArrayList<>(existing);
        merged.add(Path.of(path));
        return List.copyOf(merged);
    }

    // ================================================================
    // Client roots / access control
    // ================================================================

    /**
     * Replace the set of client-declared roots and rebuild the access
     * control policy.  Called by the MCP adapter when the client sends a
     * roots notification; other adapters may leave the root set empty and
     * rely solely on allow/deny lists.
     */
    public void setClientRoots(List<Path> roots) {
        clientRoots.clear();
        clientRoots.addAll(roots);
        rebuildAccessControl();
    }

    private void rebuildAccessControl() {
        try {
            accessControl = new PathAccessControl(
                    denyList, allowList, List.copyOf(clientRoots));
        } catch (IOException e) {
            System.err.println("ERROR: failed to rebuild path access control: "
                    + e.getMessage());
            // Keep the previous access control if we have one.  Failing to
            // canonicalize a configured path must not silently widen access;
            // the prior (or empty) policy stays in force.
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
    // Sessions (stateful, kept-open readers)
    //
    // A session-capable transport (UDS socket, gRPC) calls openSession to keep
    // a reader open across many operations, then routes subsequent calls by
    // `handle`.  The transport owns each session's lifetime and must call
    // closeSession when the client closes the handle or the connection drops.
    // See ImageSession / HeldImageReader and the withSession seam below.
    // ================================================================

    /** Default wall-clock budget for opening a session (parsing metadata). */
    private static final Duration DEFAULT_OPEN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Open an image and keep its reader open, returning a {@link SessionInfo}
     * with a handle and a SUMMARY metadata snapshot.  On any failure the reader
     * is closed and nothing is registered — a returned handle always names a
     * live, validated, open reader.
     */
    public ToolResult<SessionInfo> openSession(Map<String, Object> args) {
        final Path canonical;
        final int series;
        final Duration timeout;
        try {
            Integer seriesArg = optInt(args, "series");
            series = seriesArg != null ? seriesArg : 0;
            Duration t = optDuration(args, "timeout_seconds");
            timeout = t != null ? t : DEFAULT_OPEN_TIMEOUT;
            String rawPath = requireString(args, "path");
            var access = pathValidator().check(rawPath);
            if (access instanceof AccessResult.Denied d) {
                return ToolResult.accessDenied(d.reason());
            }
            canonical = ((AccessResult.Allowed) access).canonicalPath();
        } catch (IllegalArgumentException e) {
            return ToolResult.invalidArgument(e.getMessage());
        }

        // Open + read summary under the timeout.  Kept open on success; closed
        // on any failure so a half-opened reader never leaks or registers.
        var task = new CancellableTask(timeout);
        var opened = task.run(() -> {
            ImageReader reader = readerFactory.get();
            try {
                reader.open(canonical);
                if (series < 0 || series >= reader.getSeriesCount()) {
                    throw new IllegalArgumentException("series " + series
                            + " out of range (file has " + reader.getSeriesCount()
                            + " series)");
                }
                var summary = reader.getMetadata(series, DetailLevel.SUMMARY);
                return new OpenedReader(reader, summary);
            } catch (Throwable e) {
                try { reader.close(); } catch (IOException ignored) { /* opening failed */ }
                throw e;
            }
        });

        return switch (opened) {
            case CancellableTask.Result.Completed<OpenedReader> c -> {
                String handle = UUID.randomUUID().toString();
                sessions.put(handle,
                        new ImageSession(handle, canonical, c.value().reader()));
                yield ToolResult.success(new SessionInfo(handle, c.value().summary()));
            }
            case CancellableTask.Result.Failed<OpenedReader> f ->
                    ToolResult.convertError(f.error());
            case CancellableTask.Result.TimedOut<OpenedReader> t ->
                    ToolResult.timeout("Opening session timed out after "
                            + t.elapsed().toMillis() + " ms");
        };
    }

    /** Holder for the open reader + its summary while inside the open task. */
    private record OpenedReader(ImageReader reader, ImageMetadata summary) {}

    /**
     * Close and forget a session.  Idempotent: closing an unknown or
     * already-closed handle is a success (so a transport may close defensively
     * on disconnect without first checking).  Takes the session lock before
     * closing, so the reader is never closed out from under an in-flight
     * operation (the transport should cancel in-flight work first).
     */
    public ToolResult<Void> closeSession(String handle) {
        var session = sessions.remove(handle);
        if (session == null) {
            return ToolResult.success(null);
        }
        session.lock().lock();
        try {
            session.close();
            return ToolResult.success(null);
        } catch (IOException e) {
            return ToolResult.ioError("error closing session " + handle, e);
        } finally {
            session.lock().unlock();
        }
    }

    /** Number of currently-open sessions (for diagnostics/tests). */
    public int openSessionCount() {
        return sessions.size();
    }

    /** A read operation body, parameterized over the reader factory + args. */
    @FunctionalInterface
    private interface OpBody<T> {
        ToolResult<T> run(Supplier<ImageReader> factory, Map<String, Object> args);
    }

    /**
     * Route a read operation either statelessly (no {@code handle} → today's
     * behavior, a fresh reader per call) or against a session's already-open
     * reader (when {@code handle} is present).  In the session case the session
     * lock is held for the whole operation (the reader is not thread-safe), the
     * {@code handle} is replaced by the session's canonical {@code path}, and a
     * non-closing {@link HeldImageReader} factory is supplied so the tool's
     * try-with-resources neither re-opens nor closes the shared reader.
     */
    private <T> ToolResult<T> withSession(Map<String, Object> args, OpBody<T> body) {
        Object handleObj = args.get("handle");
        if (handleObj == null) {
            return body.run(readerFactory, args);
        }
        String handle = handleObj.toString();
        var session = sessions.get(handle);
        if (session == null) {
            return ToolResult.invalidArgument("unknown session handle: " + handle);
        }
        session.lock().lock();
        try {
            var injected = new LinkedHashMap<>(args);
            injected.remove("handle");
            injected.put("path", session.canonicalPath().toString());
            Supplier<ImageReader> heldFactory =
                    () -> new HeldImageReader(session.reader());
            return body.run(heldFactory, injected);
        } finally {
            session.lock().unlock();
        }
    }

    // ================================================================
    // Operations — each takes a flat snake_case argument map and returns
    // a structured ToolResult.  Argument-parsing problems are surfaced as
    // INVALID_ARGUMENT failures rather than thrown.  Read operations accept a
    // `handle` (session) as an alternative to `path` (stateless) via
    // withSession; export_to_tiff is path-only (a one-shot write).
    // ================================================================

    public ToolResult<ImageMetadata> inspectImage(Map<String, Object> args) {
        return withSession(args, (factory, a) -> {
            try {
                var request = InspectImageTool.Request.of(
                        requireString(a, "path"),
                        optInt(a, "series"),
                        optEnum(a, "detail", DetailLevel.class),
                        optDuration(a, "timeout_seconds"),
                        optLong(a, "max_response_bytes"));
                return InspectImageTool.execute(request, pathValidator(), factory);
            } catch (IllegalArgumentException e) {
                return ToolResult.invalidArgument(e.getMessage());
            }
        });
    }

    public ToolResult<GetThumbnailTool.ThumbnailResult> getThumbnail(
            Map<String, Object> args) {
        return withSession(args, (factory, a) -> {
            try {
                var request = GetThumbnailTool.Request.of(
                        requireString(a, "path"),
                        optInt(a, "series"),
                        optEnum(a, "projection", Projection.class),
                        requireSlice(a, "channels"),
                        optInt(a, "t"),
                        optInt(a, "max_size"),
                        optDuration(a, "timeout_seconds"),
                        optLong(a, "max_bytes"));
                return GetThumbnailTool.execute(request, pathValidator(), factory);
            } catch (IllegalArgumentException e) {
                return ToolResult.invalidArgument(e.getMessage());
            }
        });
    }

    public ToolResult<byte[]> getPlane(Map<String, Object> args) {
        return withSession(args, (factory, a) -> {
            try {
                var request = GetPlaneTool.Request.of(
                        requireString(a, "path"),
                        optInt(a, "series"),
                        optSlice(a, "channel"),
                        optSlice(a, "z"),
                        optSlice(a, "t"),
                        optBool(a, "normalize"),
                        optInt(a, "max_size"),
                        optDuration(a, "timeout_seconds"),
                        optLong(a, "max_bytes"));
                return GetPlaneTool.execute(request, pathValidator(), factory);
            } catch (IllegalArgumentException e) {
                return ToolResult.invalidArgument(e.getMessage());
            }
        });
    }

    public ToolResult<GetIntensityStatsTool.StatsResult> getIntensityStats(
            Map<String, Object> args) {
        return withSession(args, (factory, a) -> {
            try {
                // channels/z/t are slice selections (e.g. ":", "0", "0:10",
                // "0,2", "4:9,11:").  All three are required; ":" means "all"
                // and, for z/t, triggers adaptive reading within the budget.
                var request = GetIntensityStatsTool.Request.of(
                        requireString(a, "path"),
                        optInt(a, "series"),
                        requireSlice(a, "channels"),
                        requireSlice(a, "z"),
                        requireSlice(a, "t"),
                        optInt(a, "histogram_bins"),
                        optDuration(a, "timeout_seconds"),
                        optLong(a, "max_bytes"));
                return GetIntensityStatsTool.execute(
                        request, pathValidator(), factory);
            } catch (IllegalArgumentException e) {
                return ToolResult.invalidArgument(e.getMessage());
            }
        });
    }

    /** Default wall-clock budget for retrieving the metadata document. */
    private static final Duration DEFAULT_METADATA_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Return the file's extended metadata as a format-tagged
     * {@link OmeMetadata} document ({@code ome_xml}, or {@code ome_ngff} if a
     * reader supplies one).  Accepts {@code handle} or {@code path}.  An
     * optional {@code max_response_bytes} caps the document size: a document
     * larger than the cap is an {@code INVALID_ARGUMENT} failure reporting the
     * actual size (the document is never truncated — a partial XML/JSON would
     * be corrupt and give false confidence).
     */
    public ToolResult<OmeMetadata> getOmeMetadata(Map<String, Object> args) {
        return withSession(args, (factory, a) -> {
            final Path canonical;
            final Duration timeout;
            final Long maxBytes;
            try {
                String rawPath = requireString(a, "path");
                Duration t = optDuration(a, "timeout_seconds");
                timeout = t != null ? t : DEFAULT_METADATA_TIMEOUT;
                maxBytes = optLong(a, "max_response_bytes");
                var access = pathValidator().check(rawPath);
                if (access instanceof AccessResult.Denied d) {
                    return ToolResult.accessDenied(d.reason());
                }
                canonical = ((AccessResult.Allowed) access).canonicalPath();
            } catch (IllegalArgumentException e) {
                return ToolResult.invalidArgument(e.getMessage());
            }

            var task = new CancellableTask(timeout);
            return ToolResult.unwrap(task.run(() -> {
                try (var reader = factory.get()) {
                    reader.open(canonical);
                    OmeMetadata block = reader.getMetadataBlock();
                    if (block == null || block.content() == null) {
                        throw new IOException(
                                "no OME metadata is available for this file");
                    }
                    if (maxBytes != null) {
                        long size = block.content()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        if (size > maxBytes) {
                            // Never truncate a structured document.  Report the
                            // size so the caller can raise the cap (or fetch it
                            // over a transport without one).
                            throw new IllegalArgumentException(block.format()
                                    + " metadata is " + size + " bytes, exceeding "
                                    + "max_response_bytes " + maxBytes
                                    + "; raise the cap to retrieve it");
                        }
                    }
                    return block;
                }
            }));
        });
    }

    public ToolResult<ExportToTiffTool.ExportResult> exportToTiff(
            Map<String, Object> args) {
        try {
            var request = ExportToTiffTool.Request.of(
                    requireString(args, "path"),
                    requireString(args, "output_path"),
                    optInt(args, "series"),
                    requireSlice(args, "channels"),
                    requireSlice(args, "z"),
                    requireSlice(args, "t"),
                    optEnum(args, "compression", Compression.class),
                    optEnum(args, "metadata_mode", MetadataMode.class),
                    optDuration(args, "timeout_seconds"),
                    optLong(args, "max_bytes"));
            return ExportToTiffTool.execute(
                    request, pathValidator(), outputPathValidator(),
                    readerFactory, writerFactory);
        } catch (IllegalArgumentException e) {
            return ToolResult.invalidArgument(e.getMessage());
        }
    }

    public ToolResult<ExportToNgffTool.NgffResult> exportToNgff(
            Map<String, Object> args) {
        try {
            var request = ExportToNgffTool.Request.of(
                    requireString(args, "path"),
                    requireString(args, "output_path"),
                    optInt(args, "series"),
                    requireSlice(args, "channels"),
                    requireSlice(args, "z"),
                    requireSlice(args, "t"),
                    optEnum(args, "codec", ExportToNgffTool.Codec.class),
                    optInt(args, "compression_level"),
                    optInt(args, "suggested_planes_per_shard"),
                    optDuration(args, "timeout_seconds"),
                    optLong(args, "max_bytes"));
            return ExportToNgffTool.execute(
                    request, pathValidator(), outputPathValidator(),
                    readerFactory, zarrWriterFactory, blockPool());
        } catch (IllegalArgumentException e) {
            return ToolResult.invalidArgument(e.getMessage());
        }
    }

    // ================================================================
    // Shared-memory deposit
    //
    // Reads a selection (channel/Z/T ranges over a series) and writes the
    // raw pixel bytes contiguously into a client-owned region described by
    // `target`.  Unlike get_plane/get_thumbnail this is NOT a display image:
    // no normalization, no PNG — native bytes in the order documented by
    // DepositDescriptor.  See DESIGN.md §9 for the wire protocol.
    // ================================================================

    /** Default wall-clock budget for a deposit when unspecified. */
    private static final Duration DEFAULT_DEPOSIT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * A running (or finished) deposit.  Wraps a {@link CancellableTask.Handle}
     * so a transport can {@link #cancel} the fill promptly when the client
     * disconnects, and {@link #await} the structured result.  The underlying
     * {@link PixelSink} is always closed (unmapped) before {@code await}
     * returns, success or abort.
     */
    public static final class DepositHandle {
        private final CancellableTask.Handle<ToolResult<DepositDescriptor>> handle;

        private DepositHandle(
                CancellableTask.Handle<ToolResult<DepositDescriptor>> handle) {
            this.handle = handle;
        }

        /**
         * Request prompt cancellation (e.g. the client closed the connection).
         * Interrupts the fill; the half-written region is left for the client
         * to discard and is never unlinked by us.
         */
        public void cancel() {
            handle.cancel();
        }

        /** True if {@link #cancel} was called (i.e. the client went away). */
        public boolean wasCancelled() {
            return handle.isCancelRequested();
        }

        /** Block until the deposit finishes, fails, or is cancelled. */
        public ToolResult<DepositDescriptor> await() {
            return switch (handle.await()) {
                case CancellableTask.Result.Completed<ToolResult<DepositDescriptor>> c ->
                        c.value();
                case CancellableTask.Result.Failed<ToolResult<DepositDescriptor>> f ->
                        ToolResult.convertError(f.error());
                case CancellableTask.Result.TimedOut<ToolResult<DepositDescriptor>> t ->
                        ToolResult.timeout(
                            "Deposit timed out after " + t.elapsed().toMillis()
                            + " ms (interrupted " + t.interruptsSent()
                            + " time(s), thread "
                            + (t.threadStillAlive() ? "still alive" : "terminated")
                            + ")");
            };
        }
    }

    /**
     * Start a deposit on a background virtual thread and return a handle the
     * caller awaits (and may cancel).  The fill runs under a
     * {@link CancellableTask} so it honors both the {@code timeout_seconds}
     * budget and a transport-driven {@link DepositHandle#cancel}.
     */
    public DepositHandle startDeposit(Map<String, Object> args) {
        Duration timeout;
        try {
            var secs = optInt(args, "timeout_seconds");
            timeout = secs != null ? Duration.ofSeconds(secs)
                                   : DEFAULT_DEPOSIT_TIMEOUT;
        } catch (RuntimeException e) {
            timeout = DEFAULT_DEPOSIT_TIMEOUT;
        }
        var handle = new CancellableTask(timeout).start(() -> runDeposit(args));
        return new DepositHandle(handle);
    }

    /**
     * Convenience: start a deposit and block for the result, with no external
     * cancellation.  Useful for tests and request/response transports.
     */
    public ToolResult<DepositDescriptor> deposit(Map<String, Object> args) {
        return startDeposit(args).await();
    }

    /**
     * The actual read-and-write loop; runs on the CancellableTask thread.
     * Routes to either a session's open reader (when {@code handle} is present,
     * holding the session lock for the fill) or a fresh path-opened reader.
     */
    private ToolResult<DepositDescriptor> runDeposit(Map<String, Object> args) {
        try {
            Integer seriesArg = optInt(args, "series");
            int series = seriesArg != null ? seriesArg : 0;
            Slice cReq = requireSlice(args, "channels");
            Slice zReq = requireSlice(args, "z");
            Slice tReq = requireSlice(args, "t");
            Boolean dry = optBool(args, "dry_run");
            boolean dryRun = dry != null && dry;
            Map<String, Object> target = optMap(args, "target");

            Object handleObj = args.get("handle");
            if (handleObj != null) {
                String handle = handleObj.toString();
                var session = sessions.get(handle);
                if (session == null) {
                    return ToolResult.invalidArgument(
                            "unknown session handle: " + handle);
                }
                // Hold the session lock for the whole fill: the reader is not
                // thread-safe, and closeSession also takes the lock so the
                // reader can't be closed out from under us mid-read.
                session.lock().lock();
                try {
                    return depositInto(new HeldImageReader(session.reader()),
                            series, cReq, zReq, tReq, dryRun, target);
                } finally {
                    session.lock().unlock();
                }
            }

            String rawPath = requireString(args, "path");
            var srcAccess = pathValidator().check(rawPath);
            if (srcAccess instanceof AccessResult.Denied d) {
                return ToolResult.accessDenied(d.reason());
            }
            var canonicalSrc = ((AccessResult.Allowed) srcAccess).canonicalPath();

            try (var reader = readerFactory.get()) {
                reader.open(canonicalSrc);
                return depositInto(reader, series, cReq, zReq, tReq, dryRun, target);
            }
        } catch (Exception e) {
            // convertError maps IllegalArgumentException → INVALID_ARGUMENT,
            // InterruptedException → TIMEOUT, IOException → IO_ERROR, always
            // with a non-null message.  Nothing is swallowed.
            return ToolResult.convertError(e);
        }
    }

    /**
     * The deposit body given an open reader.  Resolves the selection, builds
     * the {@link DepositDescriptor}, and (unless {@code dryRun}) writes the
     * raw native pixel bytes contiguously into the client-owned region.
     * Throws on any problem; the caller maps it to a structured failure.
     */
    private ToolResult<DepositDescriptor> depositInto(
            ImageReader reader, int series,
            Slice cReq, Slice zReq, Slice tReq,
            boolean dryRun, Map<String, Object> target) throws Exception {
        if (series < 0 || series >= reader.getSeriesCount()) {
            throw new IllegalArgumentException("series " + series
                    + " out of range (file has " + reader.getSeriesCount()
                    + " series)");
        }
        var si = reader.getMetadata(series, DetailLevel.SUMMARY).detailedSeries();

        int[] cs = cReq.resolve(si.sizeC(), "channels");
        int[] zs = zReq.resolve(si.sizeZ(), "z");
        int[] ts = tReq.resolve(si.sizeT(), "t");

        int bps = si.pixelType().bytesPerPixel();
        long planeBytes = (long) si.sizeX() * si.sizeY() * bps;
        long total = planeBytes * cs.length * zs.length * ts.length;

        var descriptor = new DepositDescriptor(
                0L, total, planeBytes,
                si.pixelType().name().toLowerCase(),
                bps, si.pixelType().isSigned(),
                reader.isLittleEndian(series),
                si.sizeX(), si.sizeY(),
                cs.length, zs.length, ts.length,
                DepositDescriptor.selectionsFor(ts, cs, zs, si.sizeY(), si.sizeX()));

        // Dry run: report the size/layout so the client can allocate.
        if (dryRun) {
            return ToolResult.success(descriptor);
        }

        if (target == null) {
            throw new IllegalArgumentException(
                    "target is required unless dry_run is set");
        }
        String kind = strOr(target.get("kind"), "file");
        String targetPath = requireString(target, "path");
        Long capacity = optLong(target, "capacity_bytes");
        if (capacity == null) {
            throw new IllegalArgumentException(
                    "target.capacity_bytes is required");
        }
        if (total > capacity) {
            throw new IllegalArgumentException("required " + total
                    + " bytes exceed declared target.capacity_bytes "
                    + capacity + "; allocate a larger region");
        }

        var tgtAccess = pathValidator().check(targetPath);
        if (tgtAccess instanceof AccessResult.Denied d) {
            return ToolResult.accessDenied(d.reason());
        }
        var canonicalTarget = ((AccessResult.Allowed) tgtAccess).canonicalPath();

        try (var sink = openSink(kind, canonicalTarget, total)) {
            // C-order, axis order [t,c,z,y,x] (NGFF / OME-Zarr canonical):
            // T slowest, then C, then Z, with each (y,x) plane contiguous.
            // Iterate in memory order so writes are sequential.
            for (int ti = 0; ti < ts.length; ti++) {
                for (int ci = 0; ci < cs.length; ci++) {
                    for (int zi = 0; zi < zs.length; zi++) {
                        if (Thread.interrupted()) {
                            throw new InterruptedException("deposit cancelled");
                        }
                        byte[] plane = reader.readPlane(
                                series, cs[ci], zs[zi], ts[ti]);
                        if (plane.length != planeBytes) {
                            // Never write a short/oversized plane: that
                            // would silently corrupt the buffer.
                            throw new IOException("reader returned "
                                    + plane.length + " bytes for plane (c="
                                    + cs[ci] + ",z=" + zs[zi] + ",t=" + ts[ti]
                                    + "), expected " + planeBytes);
                        }
                        long offset = (((long) ti * cs.length + ci)
                                * zs.length + zi) * planeBytes;
                        sink.writeAt(offset, plane);
                    }
                }
            }
            sink.finish();
        }
        return ToolResult.success(descriptor);
    }

    private static PixelSink openSink(String kind, Path path, long requiredBytes)
            throws IOException {
        return switch (kind) {
            case "file" -> new MappedFileSink(path, requiredBytes);
            default -> throw new IllegalArgumentException(
                    "unsupported target kind: '" + kind
                    + "' (supported: file)");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optMap(Map<String, Object> args, String key) {
        var val = args.get(key);
        if (val == null) return null;
        if (val instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException(
                key + ": expected an object, got: "
                + val.getClass().getSimpleName());
    }

    private static String strOr(Object val, String fallback) {
        return val == null ? fallback : val.toString();
    }

    // ================================================================
    // Argument parsing helpers (shared by all adapters via this core)
    // ================================================================

    /**
     * Parse a required slice selection (e.g. "0", "0:10", "0,2", ":").
     * A missing parameter is an error — callers must be explicit (use ":"
     * for all), so a dimension can never be silently selected in full.
     */
    private static Slice requireSlice(Map<String, Object> args, String key) {
        return Slice.parse(args.get(key), key);
    }

    /** Parse an optional slice; null if the parameter is absent. */
    private static Slice optSlice(Map<String, Object> args, String key) {
        var val = args.get(key);
        return val == null ? null : Slice.parse(val, key);
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

    /**
     * Parse an enum value from a string argument (case-insensitive).
     * Handles the mapping between snake_case wire names and Java enum names.
     */
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
