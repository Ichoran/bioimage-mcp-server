package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Exports image data to <b>OME-Zarr</b> (OME-NGFF 0.5), with optional
 * subsetting.
 *
 * <p>The read/subset path mirrors {@link ExportToTiffTool}: it reads planes
 * from an {@link ImageReader} and feeds them to an {@link ImageWriter} (here a
 * {@link ZarrWriter}), passing the OME-XML through {@link OmeXmlSurgery} for
 * any subsetting.  Unlike the TIFF tool there is no {@code metadata_mode} — the
 * full (subset) OME-XML is always preserved verbatim as a sidecar inside the
 * store, since the NGFF JSON itself captures only axes and scale.
 *
 * <p>Z and T selections must resolve to a single contiguous range (as for
 * OME-TIFF); channels may be any list.  A multiscale pyramid is not yet
 * produced — a single full-resolution level is written, which is a valid
 * OME-Zarr.
 */
public final class ExportToNgffTool {

    private ExportToNgffTool() {}

    /** Zarr v3 chunk compression codec for the output. */
    public enum Codec {
        NONE("none"),
        GZIP("gzip"),
        ZSTD("zstd"),
        BLOSC("blosc");

        private final String zarrName;
        Codec(String zarrName) { this.zarrName = zarrName; }
        /** The codec name {@link ZarrWriter} expects. */
        public String zarrName() { return zarrName; }
    }

    /**
     * Parameters for an {@code export_to_ngff} call.
     *
     * @param inputPath   source image file
     * @param outputPath  output {@code .zarr} store (a directory; must not
     *                    already exist)
     * @param series      series to export (null = all)
     * @param channels    channel selection (slice; any list)
     * @param z           Z selection (slice; must be one contiguous range)
     * @param t           timepoint selection (slice; must be one contiguous range)
     * @param codec       chunk compression codec
     * @param compressionLevel  codec effort (null = the codec's default).
     *                    Lower is faster / larger; higher is slower / smaller.
     *                    Valid ranges: gzip 0–9, zstd −7…22 (negative favors
     *                    speed), blosc 0–9.  Not applicable to {@code none}.
     * @param suggestedPlanesPerShard  hint for how many Z-planes to bundle into
     *                    each on-disk shard file (null = automatic ~1 MB
     *                    sizing).  Larger values mean fewer, bigger files —
     *                    friendlier to network filesystems where per-file
     *                    latency dominates; smaller values mean more files and
     *                    finer-grained parallel (de)compression.  It is only a
     *                    suggestion: the writer picks the closest even fit
     *                    (clamped to 1…planes-per-volume).  Unlike automatic
     *                    sizing, an explicit suggestion <b>overrides</b> the
     *                    writer's file-count cap — it is honored even if it
     *                    yields many files, with a warning added to
     *                    {@link NgffResult#warnings} when the cap is exceeded.
     *                    The actual value chosen is reported per series in
     *                    {@link NgffResult#shardPlanesPerSeries}.
     * @param timeout     wall-clock time limit
     * @param maxBytes    approximate cap on raw pixel bytes to read+write
     */
    public record Request(
            String inputPath,
            String outputPath,
            Integer series,
            Slice channels,
            Slice z,
            Slice t,
            Codec codec,
            Integer compressionLevel,
            Integer suggestedPlanesPerShard,
            Duration timeout,
            long maxBytes) {

        public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
        /** Explicit default zstd effort — a good speed/size balance. */
        public static final int DEFAULT_ZSTD_LEVEL = 5;
        // A guard, not a memory bound — the export streams to disk.  Raise it
        // for very large datasets (that is exactly Zarr's sweet spot).
        public static final long DEFAULT_MAX_BYTES = 100L * 1024 * 1024 * 1024;

        public Request {
            if (inputPath == null || inputPath.isBlank()) {
                throw new IllegalArgumentException("inputPath must not be blank");
            }
            if (outputPath == null || outputPath.isBlank()) {
                throw new IllegalArgumentException("outputPath must not be blank");
            }
            if (series != null && series < 0) {
                throw new IllegalArgumentException("series must be non-negative");
            }
            if (channels == null || z == null || t == null) {
                throw new IllegalArgumentException(
                        "channels, z, and t selections are required "
                        + "(use ':' for all)");
            }
            if (codec == null) {
                throw new IllegalArgumentException("codec must not be null");
            }
            if (compressionLevel != null) {
                switch (codec) {
                    case NONE -> throw new IllegalArgumentException(
                            "compression_level is not applicable to codec 'none'");
                    case GZIP -> requireLevel(compressionLevel, 0, 9, "gzip");
                    case ZSTD -> requireLevel(compressionLevel, -7, 22, "zstd");
                    case BLOSC -> requireLevel(compressionLevel, 0, 9, "blosc");
                }
            }
            if (suggestedPlanesPerShard != null && suggestedPlanesPerShard < 1) {
                throw new IllegalArgumentException(
                        "suggested_planes_per_shard must be at least 1");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive");
            }
        }

        public static Request of(String inputPath, String outputPath,
                                  Integer series, Slice channels, Slice z, Slice t,
                                  Codec codec, Integer compressionLevel,
                                  Integer suggestedPlanesPerShard,
                                  Duration timeout, Long maxBytes) {
            Codec effectiveCodec = codec != null ? codec : Codec.ZSTD;
            // When the codec is zstd and no level is given, pin level 5
            // explicitly (rather than leaning on the library default) so the
            // common default is guaranteed and reported.  blosc keeps its own
            // lz4 + byte-shuffle default; gzip keeps its library default.
            Integer level = compressionLevel;
            if (level == null && effectiveCodec == Codec.ZSTD) {
                level = DEFAULT_ZSTD_LEVEL;
            }
            return new Request(
                    inputPath, outputPath, series, channels, z, t,
                    effectiveCodec, level, suggestedPlanesPerShard,
                    timeout != null ? timeout : DEFAULT_TIMEOUT,
                    maxBytes != null ? maxBytes : DEFAULT_MAX_BYTES);
        }

        private static void requireLevel(int v, int lo, int hi, String codec) {
            if (v < lo || v > hi) {
                throw new IllegalArgumentException(
                        "compression_level " + v + " is out of range for codec '"
                        + codec + "' (" + lo + ".." + hi + ")");
            }
        }
    }

    /**
     * Summary of an OME-Zarr export.
     *
     * @param outputPath          the written store path
     * @param bytesWritten        approximate store size on disk
     * @param seriesExported      number of series written
     * @param channelsPerSeries   channels per series in output
     * @param zSlicesPerSeries    Z-slices per series in output
     * @param timepointsPerSeries timepoints per series in output
     * @param totalPlanesWritten  total planes across all series
     * @param codec               codec used
     * @param shardPlanesPerSeries  the Z-planes-per-shard actually chosen for
     *                    each exported series (in export order; min 1, max the
     *                    series' planes-per-volume).  Always reported, whether
     *                    sizing was automatic or driven by
     *                    {@code suggested_planes_per_shard}, so the caller can
     *                    see exactly how the data was laid out on disk.
     * @param sourceFormat        source format name
     * @param omeXmlPresent       whether OME-XML was available from the reader
     * @param warnings            warnings about metadata or format conversion
     */
    public record NgffResult(
            String outputPath,
            long bytesWritten,
            int seriesExported,
            int channelsPerSeries,
            int zSlicesPerSeries,
            int timepointsPerSeries,
            long totalPlanesWritten,
            Codec codec,
            Integer compressionLevel,
            int[] shardPlanesPerSeries,
            String sourceFormat,
            boolean omeXmlPresent,
            List<String> warnings) {

        public NgffResult {
            warnings = List.copyOf(warnings);
            shardPlanesPerSeries = shardPlanesPerSeries.clone();
        }
    }

    /** Convenience overload using one validator for input and output. */
    public static ToolResult<NgffResult> execute(
            Request request,
            PathValidator pathValidator,
            Supplier<ImageReader> readerFactory,
            Supplier<ImageWriter> writerFactory,
            BlockPool pool) {
        return execute(request, pathValidator, pathValidator,
                readerFactory, writerFactory, pool);
    }

    public static ToolResult<NgffResult> execute(
            Request request,
            PathValidator inputPathValidator,
            PathValidator outputPathValidator,
            Supplier<ImageReader> readerFactory,
            Supplier<ImageWriter> writerFactory,
            BlockPool pool) {

        var inputAccess = inputPathValidator.check(request.inputPath());
        if (inputAccess instanceof AccessResult.Denied denied) {
            return ToolResult.accessDenied("input: " + denied.reason());
        }
        var inputPath = ((AccessResult.Allowed) inputAccess).canonicalPath();

        var outputAccess = outputPathValidator.check(request.outputPath());
        if (outputAccess instanceof AccessResult.Denied denied) {
            return ToolResult.accessDenied("output: " + denied.reason());
        }
        var outputPath = ((AccessResult.Allowed) outputAccess).canonicalPath();

        if (inputPath.equals(outputPath)) {
            return ToolResult.invalidArgument(
                    "input and output paths must be different");
        }

        var task = new CancellableTask(request.timeout());
        var result = task.run(() -> {
            try (var reader = readerFactory.get()) {
                reader.open(inputPath);

                int seriesCount = reader.getSeriesCount();
                int[] seriesToExport;
                if (request.series() != null) {
                    if (request.series() >= seriesCount) {
                        throw new IllegalArgumentException(
                                "series " + request.series()
                                + " out of range, file has "
                                + seriesCount + " series");
                    }
                    seriesToExport = new int[] { request.series() };
                } else {
                    seriesToExport = new int[seriesCount];
                    for (int i = 0; i < seriesCount; i++) seriesToExport[i] = i;
                }

                var meta = reader.getMetadata(
                        seriesToExport[0], DetailLevel.STANDARD);
                var si = meta.detailedSeries();

                Range zRange = request.z().resolveContiguous(si.sizeZ(), "z");
                Range tRange = request.t().resolveContiguous(si.sizeT(), "t");
                int zStart = zRange.start(), zEnd = zRange.end();
                int tStart = tRange.start(), tEnd = tRange.end();
                int[] channels = request.channels().resolve(si.sizeC(), "channels");

                int zCount = zEnd - zStart + 1;
                int tCount = tEnd - tStart + 1;
                long bytesPerPlane = (long) si.sizeX() * si.sizeY()
                        * si.pixelType().bytesPerPixel();
                long totalPlanes = (long) channels.length * zCount
                        * tCount * seriesToExport.length;
                long totalBytes = bytesPerPlane * totalPlanes;
                if (totalBytes > request.maxBytes()) {
                    throw new IllegalArgumentException(
                            "export requires " + totalBytes
                            + " bytes of pixel data, which exceeds the maxBytes"
                            + " budget (" + request.maxBytes() + "). Raise"
                            + " max_bytes or subset channels, Z, or T.");
                }

                var warnings = new ArrayList<String>();
                String omeXml = reader.getOMEXML();
                boolean omeXmlPresent = omeXml != null;

                if (omeXml != null) {
                    var spec = new OmeXmlSurgery.SubsetSpec(
                            request.series(), channels,
                            zStart, zEnd, tStart, tEnd);
                    omeXml = OmeXmlSurgery.subset(omeXml, spec).xml();
                } else {
                    warnings.add("No OME-XML available from the reader; a"
                            + " minimal NGFF metadata header was generated."
                            + " Physical pixel sizes are unknown, so the"
                            + " coordinate scale defaults to 1.0.");
                    omeXml = buildMinimalOmeXml(
                            si, reader.isLittleEndian(seriesToExport[0]),
                            channels.length, zCount, tCount);
                }

                String formatName = meta.formatName();
                if (!formatName.toLowerCase().contains("zarr")
                        && !formatName.toLowerCase().contains("ngff")) {
                    warnings.add("Source format: " + formatName
                            + ". Format-specific metadata not captured in the"
                            + " OME model is preserved only in the embedded"
                            + " OME-XML sidecar (OME/METADATA.ome.xml).");
                }

                String codecSpec = request.codec().zarrName()
                        + (request.compressionLevel() != null
                            ? ":" + request.compressionLevel() : "");

                int planeLen = (int) bytesPerPlane;

                var writer = writerFactory.get();
                if (request.suggestedPlanesPerShard() != null) {
                    writer.suggestShardPlanes(request.suggestedPlanesPerShard());
                }
                // Open is the one phase where we must NOT delete on failure: an
                // "already exists" refusal means the path is the user's, not a
                // partial store we created.
                try {
                    writer.open(outputPath, omeXml, codecSpec);
                } catch (Exception e) {
                    closeQuietly(writer);
                    throw e;
                }

                // The writer has now chosen each series' shard depth: from the
                // byte heuristic (which the file-count cap may coarsen) or from
                // our suggestion (which overrides that cap, with a warning).
                // Capture it so the caller always learns the actual on-disk
                // layout — not merely what was requested.
                int[] shardPlanes = new int[seriesToExport.length];
                for (int i = 0; i < seriesToExport.length; i++) {
                    shardPlanes[i] = writer.preferredBlockDepth(i);
                }
                // Surface any layout warnings (e.g. a suggestion that overrode
                // the file-count cap) so the caller sees the cost of the choice.
                warnings.addAll(writer.layoutWarnings());

                // From here on we own the store, so any failure cleans it up.
                var firstError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var planesWritten = new java.util.concurrent.atomic.AtomicLong();
                try {
                    for (int seriesIdx = 0;
                         seriesIdx < seriesToExport.length; seriesIdx++) {
                        int s = seriesToExport[seriesIdx];
                        writer.setSeries(seriesIdx);
                        // Hand off whole shard blocks; distinct blocks address
                        // disjoint shard files, so workers never collide.
                        int blockZ = Math.max(1, writer.preferredBlockDepth(seriesIdx));
                        var futures = new ArrayList<java.util.concurrent.Future<?>>();

                        readLoop:
                        for (int ti = 0; ti < tCount; ti++) {
                            int t = tStart + ti;
                            for (int ci = 0; ci < channels.length; ci++) {
                                int ch = channels[ci];
                                for (int zb = 0; zb < zCount; zb += blockZ) {
                                    if (firstError.get() != null) break readLoop;
                                    int bz = Math.min(blockZ, zCount - zb);
                                    byte[] block = new byte[bz * planeLen];
                                    for (int k = 0; k < bz; k++) {
                                        byte[] p = reader.readPlane(
                                                s, ch, zStart + zb + k, t);
                                        if (p.length != planeLen) {
                                            throw new java.io.IOException(
                                                "plane size " + p.length
                                                + " != expected " + planeLen);
                                        }
                                        System.arraycopy(
                                                p, 0, block, k * planeLen, planeLen);
                                    }
                                    final int fc = ci, fzb = zb, ft = ti, fbz = bz;
                                    futures.add(pool.submit(() -> {
                                        if (firstError.get() != null) return;
                                        try {
                                            writer.writeBlock(0, fc, fzb, ft, fbz, block);
                                            planesWritten.addAndGet(fbz);
                                        } catch (Throwable th) {
                                            firstError.compareAndSet(null, th);
                                        }
                                    }));
                                }
                            }
                        }
                        // Barrier: all of this series' shard writes must finish
                        // before setSeries re-points the writer for the next.
                        BlockPool.awaitAll(futures);
                        if (firstError.get() != null) break;
                    }
                } catch (Throwable th) {
                    firstError.compareAndSet(null, th);
                }

                if (firstError.get() != null) {
                    // We created this store and it is incomplete — remove it so
                    // it can never be mistaken for a valid export, then fail.
                    closeQuietly(writer);
                    deleteRecursively(outputPath);
                    Throwable err = firstError.get();
                    throw new java.io.IOException(
                            "OME-Zarr export failed and the partial store was "
                            + "removed: " + err.getMessage(), err);
                }

                long bytesWritten = writer.getBytesWritten();
                closeQuietly(writer);
                return new NgffResult(
                        outputPath.toString(),
                        bytesWritten,
                        seriesToExport.length,
                        channels.length, zCount, tCount,
                        planesWritten.get(),
                        request.codec(),
                        request.compressionLevel(),
                        shardPlanes,
                        formatName,
                        omeXmlPresent,
                        warnings);
            }
        });

        return ToolResult.unwrap(result);
    }

    private static void closeQuietly(ImageWriter writer) {
        try { writer.close(); } catch (Exception ignored) { }
    }

    /** Recursively delete a partial store; best-effort. */
    private static void deleteRecursively(java.nio.file.Path root) {
        if (root == null || !java.nio.file.Files.exists(root)) return;
        try (var walk = java.nio.file.Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { java.nio.file.Files.deleteIfExists(p); }
                catch (java.io.IOException ignored) { }
            });
        } catch (java.io.IOException ignored) { }
    }

    /**
     * Build a minimal OME-XML when the reader provides none.  Includes the
     * {@code BigEndian} attribute ({@link ZarrWriter} requires it for
     * multi-byte pixel types) but no physical sizes.
     */
    static String buildMinimalOmeXml(
            SeriesInfo si, boolean littleEndian,
            int sizeC, int sizeZ, int sizeT) {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<OME xmlns=\"http://www.openmicroscopy.org/Schemas/OME/2016-06\">\n");
        sb.append("  <Image ID=\"Image:0\" Name=\"export\">\n");
        sb.append("    <Pixels ID=\"Pixels:0\" DimensionOrder=\"XYCZT\"")
          .append(" BigEndian=\"").append(!littleEndian).append("\"")
          .append(" SizeX=\"").append(si.sizeX()).append("\"")
          .append(" SizeY=\"").append(si.sizeY()).append("\"")
          .append(" SizeZ=\"").append(sizeZ).append("\"")
          .append(" SizeC=\"").append(sizeC).append("\"")
          .append(" SizeT=\"").append(sizeT).append("\"")
          .append(" Type=\"").append(omePixelType(si.pixelType())).append("\">\n");
        for (int i = 0; i < sizeC; i++) {
            sb.append("      <Channel ID=\"Channel:0:").append(i)
              .append("\" SamplesPerPixel=\"1\"/>\n");
        }
        sb.append("    </Pixels>\n  </Image>\n</OME>\n");
        return sb.toString();
    }

    private static String omePixelType(PixelType type) {
        return switch (type) {
            case BIT    -> "bit";
            case INT8   -> "int8";
            case UINT8  -> "uint8";
            case INT16  -> "int16";
            case UINT16 -> "uint16";
            case INT32  -> "int32";
            case UINT32 -> "uint32";
            case FLOAT  -> "float";
            case DOUBLE -> "double";
        };
    }
}
