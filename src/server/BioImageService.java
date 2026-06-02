package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.Compression;
import lab.kerrr.mcpbio.bioimageserver.ExportToTiffTool.MetadataMode;
import lab.kerrr.mcpbio.bioimageserver.GetIntensityStatsTool.Range;
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
import java.util.List;
import java.util.Map;
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

    private BioImageService(List<Path> denyList, List<Path> allowList,
                            Supplier<ImageReader> readerFactory,
                            Supplier<ImageWriter> writerFactory) {
        this.denyList = List.copyOf(denyList);
        this.allowList = List.copyOf(allowList);
        this.readerFactory = readerFactory;
        this.writerFactory = writerFactory;
        rebuildAccessControl();
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

        public BioImageService build() {
            return new BioImageService(denyList, allowList,
                    readerFactory, writerFactory);
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
    // Operations — each takes a flat snake_case argument map and returns
    // a structured ToolResult.  Argument-parsing problems are surfaced as
    // INVALID_ARGUMENT failures rather than thrown.
    // ================================================================

    public ToolResult<ImageMetadata> inspectImage(Map<String, Object> args) {
        try {
            var request = InspectImageTool.Request.of(
                    requireString(args, "path"),
                    optInt(args, "series"),
                    optEnum(args, "detail", DetailLevel.class),
                    optDuration(args, "timeout_seconds"),
                    optLong(args, "max_response_bytes"));
            return InspectImageTool.execute(request, pathValidator(), readerFactory);
        } catch (IllegalArgumentException e) {
            return ToolResult.invalidArgument(e.getMessage());
        }
    }

    public ToolResult<GetThumbnailTool.ThumbnailResult> getThumbnail(
            Map<String, Object> args) {
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
            return GetThumbnailTool.execute(request, pathValidator(), readerFactory);
        } catch (IllegalArgumentException e) {
            return ToolResult.invalidArgument(e.getMessage());
        }
    }

    public ToolResult<byte[]> getPlane(Map<String, Object> args) {
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
            return GetPlaneTool.execute(request, pathValidator(), readerFactory);
        } catch (IllegalArgumentException e) {
            return ToolResult.invalidArgument(e.getMessage());
        }
    }

    public ToolResult<GetIntensityStatsTool.StatsResult> getIntensityStats(
            Map<String, Object> args) {
        try {
            // Single-value params (channel, z_slice, timepoint) are shortcuts
            // for a Range of one element.  Range params (channel_start/end,
            // z_start/end, t_start/end) allow specifying inclusive ranges.
            // Using both forms for the same dimension is an error.
            Range channels = parseRange(args,
                    "channel", "channel_start", "channel_end");
            Range zRange = parseRange(args, "z_slice", "z_start", "z_end");
            Range tRange = parseRange(args, "timepoint", "t_start", "t_end");

            var request = GetIntensityStatsTool.Request.of(
                    requireString(args, "path"),
                    optInt(args, "series"),
                    channels,
                    zRange,
                    tRange,
                    optInt(args, "histogram_bins"),
                    optDuration(args, "timeout_seconds"),
                    optLong(args, "max_bytes"));
            return GetIntensityStatsTool.execute(
                    request, pathValidator(), readerFactory);
        } catch (IllegalArgumentException e) {
            return ToolResult.invalidArgument(e.getMessage());
        }
    }

    public ToolResult<ExportToTiffTool.ExportResult> exportToTiff(
            Map<String, Object> args) {
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
            return ExportToTiffTool.execute(
                    request, pathValidator(), outputPathValidator(),
                    readerFactory, writerFactory);
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

    /** The actual read-and-write loop; runs on the CancellableTask thread. */
    private ToolResult<DepositDescriptor> runDeposit(Map<String, Object> args) {
        try {
            String rawPath = requireString(args, "path");
            Integer seriesArg = optInt(args, "series");
            int series = seriesArg != null ? seriesArg : 0;
            Range cReq = parseRange(args, "channel", "channel_start", "channel_end");
            Range zReq = parseRange(args, "z_slice", "z_start", "z_end");
            Range tReq = parseRange(args, "timepoint", "t_start", "t_end");
            Boolean dry = optBool(args, "dry_run");
            boolean dryRun = dry != null && dry;
            Map<String, Object> target = optMap(args, "target");

            var srcAccess = pathValidator().check(rawPath);
            if (srcAccess instanceof AccessResult.Denied d) {
                return ToolResult.accessDenied(d.reason());
            }
            var canonicalSrc = ((AccessResult.Allowed) srcAccess).canonicalPath();

            try (var reader = readerFactory.get()) {
                reader.open(canonicalSrc);
                if (series < 0 || series >= reader.getSeriesCount()) {
                    throw new IllegalArgumentException("series " + series
                            + " out of range (file has " + reader.getSeriesCount()
                            + " series)");
                }
                var si = reader.getMetadata(series, DetailLevel.SUMMARY)
                        .detailedSeries();

                var c = (cReq != null ? cReq : new Range(0, -1))
                        .resolve(si.sizeC(), "channel");
                var z = (zReq != null ? zReq : new Range(0, -1))
                        .resolve(si.sizeZ(), "Z-slice");
                var t = (tReq != null ? tReq : new Range(0, -1))
                        .resolve(si.sizeT(), "timepoint");

                int bps = si.pixelType().bytesPerPixel();
                long planeBytes = (long) si.sizeX() * si.sizeY() * bps;
                long total = planeBytes * c.count() * z.count() * t.count();

                var descriptor = new DepositDescriptor(
                        0L, total, planeBytes,
                        si.pixelType().name().toLowerCase(),
                        bps, si.pixelType().isSigned(),
                        reader.isLittleEndian(series),
                        si.sizeX(), si.sizeY(),
                        c.count(), z.count(), t.count());

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
                var canonicalTarget =
                        ((AccessResult.Allowed) tgtAccess).canonicalPath();

                int[] cs = c.toArray(), zs = z.toArray(), ts = t.toArray();
                try (var sink = openSink(kind, canonicalTarget, total)) {
                    for (int ti = 0; ti < ts.length; ti++) {
                        for (int zi = 0; zi < zs.length; zi++) {
                            for (int ci = 0; ci < cs.length; ci++) {
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
                                long offset = (((long) ti * zs.length + zi)
                                        * cs.length + ci) * planeBytes;
                                sink.writeAt(offset, plane);
                            }
                        }
                    }
                    sink.finish();
                }
                return ToolResult.success(descriptor);
            }
        } catch (Exception e) {
            // convertError maps IllegalArgumentException → INVALID_ARGUMENT,
            // InterruptedException → TIMEOUT, IOException → IO_ERROR, always
            // with a non-null message.  Nothing is swallowed.
            return ToolResult.convertError(e);
        }
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
