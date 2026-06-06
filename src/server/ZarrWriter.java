package lab.kerrr.mcpbio.bioimageserver;

import dev.zarr.zarrjava.core.Attributes;
import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.StoreHandle;
import dev.zarr.zarrjava.v3.Array;
import dev.zarr.zarrjava.v3.DataType;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@link ImageWriter} implementation that writes <b>OME-Zarr</b> conforming to
 * <b>OME-NGFF 0.5</b> (which is Zarr v3), backed by
 * {@code dev.zarr:zarr-java}.
 *
 * <p>All zarr-java API usage is confined to this class, mirroring how
 * {@link BioFormatsWriter} confines Bio-Formats.
 *
 * <p><b>Layout.</b>  We always use the {@code bioformats2raw.layout}
 * convention so single- and multi-series exports are handled uniformly and
 * the source OME-XML is preserved losslessly:
 * <pre>
 *   root.zarr/
 *     zarr.json                      group, attr {"bioformats2raw.layout": 3}
 *     OME/METADATA.ome.xml           the full (subset) OME-XML, verbatim
 *     0/                             series 0: an OME-NGFF 0.5 image group
 *       zarr.json                    attr {"ome": {version, multiscales:[…]}}
 *       0/                           the single resolution-level array
 *     1/  …                          further series, if any
 * </pre>
 * Only a single resolution level (path {@code "0"}) is written; a multiscale
 * pyramid is deferred (see PLAN.md Phase 15b).  A single-level image is a
 * valid OME-Zarr.
 *
 * <p><b>Axis order</b> is the NGFF canonical {@code [t,c,z,y,x]} — the same
 * order the shared-memory deposit uses (DESIGN.md §9.3) — so a mapped array
 * drops into napari/zarr/dask without a transpose.
 *
 * <p>Pixel bytes from {@link ImageReader#readPlane} are in the source's
 * native byte order; this writer reads that order from the OME-XML
 * {@code Pixels/@BigEndian} attribute.  For multi-byte pixel types the
 * attribute is <b>required</b> — we refuse rather than guess endianness and
 * risk silently byte-swapped data.
 */
public final class ZarrWriter implements ImageWriter {

    /** ~1 MB: the block size where per-item allocation/IO overhead flattens. */
    static final long TARGET_SHARD_BYTES = 1L << 20;
    /** Volumes smaller than this go in a single shard (one file per t,c). */
    static final long SMALL_VOLUME_BYTES = 1L << 22;   // 4 MB
    /** Cap on chunk files; we coarsen shards (up to whole volumes) to stay under. */
    static final int MAX_FILES = 128 * 1024;

    /**
     * A shard's extent on the {@code (T, C, Z)} axes (Y and X are always the
     * full plane).  {@code [1,1,Z]} is plain Z-sharding; {@code [1,C,Z]} bundles
     * all channels (the whole per-timepoint volume); {@code [T,1,1]} shards a
     * pure time series across T.  Package-private so the planner is testable.
     */
    record ShardShape(int t, int c, int z) {
        int planes() { return t * c * z; }
    }

    /** One exported series: its open array plus what writeShardBlock needs. */
    private record SeriesArray(
            Array array, int sizeY, int sizeX, ShardShape shard,
            ucar.ma2.DataType ma2Type, int bytesPerSample, ByteOrder order) {}

    private Path root;
    private String codec;
    private int suggestedShardPlanes = 0;   // 0 = none; use the byte heuristic
    private final List<SeriesArray> series = new ArrayList<>();
    private final List<String> layoutWarnings = new ArrayList<>();
    private volatile int current = -1;

    @Override
    public void suggestShardPlanes(int planes) {
        // Clamp the lower bound here; the per-series upper bound (planes per
        // volume) is applied in computeShardDepths, since each series differs.
        this.suggestedShardPlanes = Math.max(1, planes);
    }

    @Override
    public void open(Path path, String omeXml, String compression)
            throws IOException {
        this.root = path.toAbsolutePath();
        this.codec = (compression == null || compression.isBlank())
                ? "zstd" : compression.toLowerCase();

        // Never destroy existing data: the store is a directory tree, and an
        // export must not overwrite whatever is already there.
        if (Files.exists(root)) {
            throw new IOException(
                    "output path already exists: " + root
                    + " (refusing to overwrite an existing OME-Zarr store)");
        }
        if (omeXml == null || omeXml.isBlank()) {
            throw new IOException("ZarrWriter requires OME-XML metadata");
        }

        try {
            var store = new FilesystemStore(root);

            // Root group: bioformats2raw.layout marker.
            var rootAttrs = new Attributes();
            rootAttrs.set("bioformats2raw.layout", 3);
            dev.zarr.zarrjava.v3.Group.create(store.resolve(), rootAttrs);

            // Preserve the source metadata verbatim (lossless), since the NGFF
            // JSON below captures only axes/scale, not channels/instrument/etc.
            Path omeDir = root.resolve("OME");
            Files.createDirectories(omeDir);
            Files.writeString(omeDir.resolve("METADATA.ome.xml"), omeXml);

            List<Element> images = parsePixels(omeXml);
            if (images.isEmpty()) {
                throw new IOException(
                        "OME-XML contains no Image/Pixels elements");
            }

            // Plan each series' shard shape (T,C,Z extents) up front, so the
            // global file-count cap can see all series together.
            int n = images.size();
            long[] planeBytes = new long[n];
            int[] sizeC = new int[n];
            int[] sizeZ = new int[n];
            int[] sizeT = new int[n];
            for (int s = 0; s < n; s++) {
                Element px = images.get(s);
                planeBytes[s] = (long) intAttr(px, "SizeX") * intAttr(px, "SizeY")
                        * mapDataType(px.getAttribute("Type")).getByteCount();
                sizeC[s] = intAttr(px, "SizeC");
                sizeZ[s] = intAttr(px, "SizeZ");
                sizeT[s] = intAttr(px, "SizeT");
            }
            ShardShape[] plans = computeShardPlans(
                    planeBytes, sizeC, sizeZ, sizeT, MAX_FILES, suggestedShardPlanes);

            // An explicit suggestion overrides the file-count cap (the user
            // asked for this shard size).  We honor it, but never silently — if
            // the resulting layout exceeds the cap, warn so the caller can see
            // the file-count cost of their choice.
            if (suggestedShardPlanes > 0) {
                String w = capOverrideWarning(
                        totalShardFiles(plans, sizeC, sizeZ, sizeT), MAX_FILES);
                if (w != null) layoutWarnings.add(w);
            }

            for (int s = 0; s < n; s++) {
                series.add(createSeries(store, s, images.get(s), plans[s]));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(
                    "Failed to initialize OME-Zarr store: " + e.getMessage(), e);
        }
    }

    /** Build one series' image group + resolution-level array. */
    private SeriesArray createSeries(FilesystemStore store, int s, Element pixels,
                                     ShardShape shard) throws Exception {
        int sizeX = intAttr(pixels, "SizeX");
        int sizeY = intAttr(pixels, "SizeY");
        int sizeZ = intAttr(pixels, "SizeZ");
        int sizeC = intAttr(pixels, "SizeC");
        int sizeT = intAttr(pixels, "SizeT");
        String typeStr = pixels.getAttribute("Type");
        DataType dataType = mapDataType(typeStr);
        int bytesPerSample = dataType.getByteCount();

        ByteOrder order = readByteOrder(pixels, bytesPerSample);

        // Physical pixel sizes → coordinateTransformations scale; default 1.0.
        double scaleX = doubleAttr(pixels, "PhysicalSizeX", 1.0);
        double scaleY = doubleAttr(pixels, "PhysicalSizeY", 1.0);
        double scaleZ = doubleAttr(pixels, "PhysicalSizeZ", 1.0);
        String spaceUnit = ngffUnit(pixels.getAttribute("PhysicalSizeXUnit"));

        // Image group with NGFF 0.5 metadata: multiscales (axes + scale) plus,
        // when the source has them, an `omero` block surfacing channel
        // names/colors up front (what napari/vizarr read) rather than leaving
        // them buried in the OME-XML sidecar.
        String imageName = imageName(pixels);
        var ome = multiscales(imageName, spaceUnit, scaleZ, scaleY, scaleX);
        var omero = buildOmero(pixels, dataType, imageName);
        if (omero != null) ome.put("omero", omero);
        var imageAttrs = new Attributes();
        imageAttrs.set("ome", ome);
        StoreHandle imageHandle = store.resolve(String.valueOf(s));
        dev.zarr.zarrjava.v3.Group.create(imageHandle, imageAttrs);

        // The single resolution-level array.  The inner chunk is always one
        // whole plane (ideal for plane-based microscopy reads — exactly one
        // plane decompressed per access).  When the shard spans more than one
        // plane we bundle those plane-chunks into one shard file, so the file
        // count stays sane for volumetric/timeseries/multichannel data without
        // changing the read granularity.  The shard may extend along Z, across
        // channels ([1,C,Z]), or across time ([T,1,1]) — see computeShardPlans.
        var b = Array.metadataBuilder()
                .withShape(sizeT, sizeC, sizeZ, sizeY, sizeX)
                .withDataType(dataType)
                .withFillValue(0)
                .withDimensionNames("t", "c", "z", "y", "x");
        if (shard.planes() > 1) {
            b = b.withChunkShape(shard.t(), shard.c(), shard.z(), sizeY, sizeX) // shard = file
                 .withCodecs(cb -> cb.withSharding(
                         new int[]{1, 1, 1, sizeY, sizeX},      // inner = plane
                         this::applyInnerCodec));
        } else {
            b = b.withChunkShape(1, 1, 1, sizeY, sizeX)
                 .withCodecs(this::applyInnerCodec);
        }
        Array array = Array.create(store.resolve(String.valueOf(s), "0"), b.build());

        return new SeriesArray(
                array, sizeY, sizeX, shard, dataType.getMA2DataType(),
                bytesPerSample, order);
    }

    // ================================================================
    // Sharding policy (file count vs. write-buffer size) — see PLAN Phase 16.
    // ================================================================

    /** No-suggestion overload: size every shard by the byte heuristic. */
    static int[] computeShardDepths(long[] planeBytes, int[] sizeZ,
                                    long[] tc, int maxFiles) {
        return computeShardDepths(planeBytes, sizeZ, tc, maxFiles, 0);
    }

    /**
     * Choose the shard depth (Z-planes per file) for each series.
     *
     * <p>When {@code suggestedPlanes <= 0}, sizing is automatic, per series: a
     * plane already over the ~1 MB sweet spot stays one file per plane; a
     * volume under 4 MB goes in a single file; otherwise shardZ is the smallest
     * power of two whose block reaches ~1 MB.
     *
     * <p>When {@code suggestedPlanes > 0}, that hint replaces the byte
     * heuristic: each series' shard depth is {@link #fitShardPlanes} of the
     * suggestion against that series' Z-depth (the closest even-ish fit,
     * clamped to 1…Z).
     *
     * <p>With automatic sizing only, the file-count cap is a hard backstop:
     * if the total chunk-file count would exceed {@code maxFiles}, shard depths
     * are doubled (never past a whole volume) until the count drops under the
     * cap.  An <b>explicit suggestion overrides the cap entirely</b> — the
     * user's requested shard size is honored even if it produces more files;
     * {@link #open} emits a {@link #layoutWarnings layout warning} in that case
     * rather than quietly coarsening.  Either way callers report the value
     * finally chosen (via {@link #preferredBlockShape}).
     */
    static int[] computeShardDepths(long[] planeBytes, int[] sizeZ,
                                    long[] tc, int maxFiles, int suggestedPlanes) {
        int n = planeBytes.length;
        int[] shardZ = new int[n];
        for (int i = 0; i < n; i++) {
            shardZ[i] = (suggestedPlanes > 0)
                    ? fitShardPlanes(sizeZ[i], suggestedPlanes)
                    : autoShardCount(planeBytes[i], sizeZ[i]);
        }
        // The cap coarsens only automatic sizing; an explicit suggestion wins.
        if (suggestedPlanes <= 0) {
            while (totalFiles(shardZ, sizeZ, tc) > maxFiles
                    && canGrow(shardZ, sizeZ)) {
                for (int i = 0; i < n; i++) {
                    if (shardZ[i] < sizeZ[i]) {
                        shardZ[i] = Math.min(sizeZ[i], shardZ[i] * 2);
                    }
                }
            }
        }
        return shardZ;
    }

    /**
     * The warning emitted when an explicit shard suggestion drives the file
     * count past {@code maxFiles}; {@code null} when the count is within the
     * cap.  Honoring the suggestion is correct, but the caller must be told the
     * cap was exceeded (many small files are slow to list and to serve over a
     * network filesystem).
     */
    static String capOverrideWarning(long fileCount, int maxFiles) {
        if (fileCount <= maxFiles) return null;
        return "suggested_planes_per_shard produced " + fileCount
                + " shard files, exceeding the recommended cap of " + maxFiles
                + ". The suggestion was honored as requested, but this many"
                + " files can be slow to list and to serve over a network"
                + " filesystem; raise suggested_planes_per_shard for fewer,"
                + " larger files.";
    }

    @Override
    public List<String> layoutWarnings() {
        return List.copyOf(layoutWarnings);
    }

    /**
     * Given a volume of {@code n} planes and a <em>suggested</em> {@code k}
     * planes per shard, return the actual planes-per-shard to use: the value
     * closest to {@code k} that divides the volume into shards with low
     * overshoot, clamped to {@code [1, n]}.
     *
     * <p>Two candidates bracket {@code k}: with {@code v = ceil(n/k)} shards the
     * even fit is {@code k' = ceil(n/v) <= k}; with {@code u = floor(n/k)} shards
     * it is {@code k'' = ceil(n/u) >= k}.  We prefer {@code k'} when its shards
     * cover strictly fewer total planes ({@code v*k' < u*k''}); otherwise we
     * prefer {@code k''} when it is multiplicatively at least as close to
     * {@code k} ({@code k''*k' <= k*k}).  In the remaining case {@code k''} is
     * the bigger relative jump but {@code k'} wastes more space, so we weigh the
     * two: the log size disparity {@code log(k''/k) - log(k/k')} (positive) plus
     * the log nominal-space disparity {@code log(u*k''/n) - log(v*k'/n)}
     * (negative).  A positive sum means {@code k''} strays too far from {@code k}
     * for the tighter packing to be worth it, so we take {@code k'}; otherwise
     * {@code k''}.
     */
    static int fitShardPlanes(int n, int k) {
        if (n <= 1 || k <= 1) return 1;
        if (k >= n) return n;                  // whole volume in one shard
        int v = ceilDiv(n, k);                 // more shards  → kp <= k
        int kp = ceilDiv(n, v);
        int u = n / k;                         // fewer shards → kpp >= k (u >= 1)
        int kpp = ceilDiv(n, u);
        if (kp == kpp) return kp;              // k divides cleanly, etc.

        long coverDown = (long) v * kp;        // planes covered by the kp packing
        long coverUp = (long) u * kpp;         //   "       "      "  the kpp packing
        if (coverDown < coverUp) return kp;
        // coverDown >= coverUp: the kpp packing is at least as tight.
        if ((long) kpp * kp <= (long) k * k) return kpp;   // kpp also ≥-as-close to k
        // kp is the closer ratio but kpp packs tighter — weigh size vs. space.
        double sizeDisparity = Math.log((double) kpp / k) - Math.log((double) k / kp);
        double spaceDisparity = Math.log((double) coverUp / n)
                              - Math.log((double) coverDown / n);
        return (sizeDisparity + spaceDisparity > 0) ? kp : kpp;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * The automatic byte heuristic for one axis: how many planes to bundle per
     * shard to land near the ~1 MB sweet spot.  A plane already over the target
     * stays one per shard; a whole axis under the small-volume threshold goes in
     * one shard; otherwise the smallest power of two whose block reaches ~1 MB,
     * capped at the axis size.  Used for both the Z and (time-series) T axes.
     */
    static int autoShardCount(long pb, int axisSize) {
        if (pb > TARGET_SHARD_BYTES) return 1;
        if ((long) axisSize * pb < SMALL_VOLUME_BYTES) return axisSize;
        int s = 1;
        while ((long) s * pb < TARGET_SHARD_BYTES && s < axisSize) s <<= 1;
        return Math.max(1, Math.min(s, axisSize));
    }

    // ================================================================
    // Shape-aware shard planning (T/C/Z extents) — used by open().
    // ================================================================

    /**
     * Plan every series' shard shape, then (for automatic sizing only) coarsen
     * to honor the global file-count cap.  An explicit suggestion overrides the
     * cap, exactly as for {@link #computeShardDepths}.
     */
    static ShardShape[] computeShardPlans(long[] planeBytes, int[] sizeC,
            int[] sizeZ, int[] sizeT, int maxFiles, int suggestion) {
        int n = planeBytes.length;
        ShardShape[] plans = new ShardShape[n];
        for (int i = 0; i < n; i++) {
            plans[i] = planSeries(
                    planeBytes[i], sizeC[i], sizeZ[i], sizeT[i], suggestion);
        }
        if (suggestion <= 0) {
            while (totalShardFiles(plans, sizeC, sizeZ, sizeT) > maxFiles
                    && growShards(plans, sizeC, sizeZ, sizeT)) {
                // loop: growShards advances one doubling step per pass
            }
        }
        return plans;
    }

    /**
     * Choose one series' shard shape.  The default is Z-sharding (one
     * channel/timepoint per shard; {@link #fitShardPlanes}/{@link #autoShardCount}
     * along Z).  Two shape-aware cases give the flexibility that
     * volumetric/timeseries/multichannel files need — especially over a network
     * filesystem where file count dominates:
     *
     * <ul>
     *   <li><b>Pure time series</b> ({@code C==1 && Z==1}): there is no Z to
     *       bundle, so shard across <b>T</b> instead, applying the same sizing
     *       logic to the T axis.</li>
     *   <li><b>Multichannel</b> ({@code C>1}): if the target planes-per-shard is
     *       closer in <i>log space</i> to {@code C*Z} than to {@code Z}, emit one
     *       shard per timepoint spanning all channels and Z ({@code [1,C,Z]} =
     *       the whole per-timepoint volume).  The crossover is at
     *       {@code Z*sqrt(C)}, i.e. {@code target^2 > C*Z^2}.</li>
     * </ul>
     *
     * "Target planes-per-shard" is the explicit suggestion, or (automatic) the
     * plane count that reaches ~1 MB.
     */
    static ShardShape planSeries(long pb, int c, int z, int t, int suggestion) {
        if (c == 1 && z == 1) {                      // pure time series → shard T
            int st = (suggestion > 0) ? fitShardPlanes(t, suggestion)
                                      : autoShardCount(pb, t);
            return new ShardShape(st, 1, 1);
        }
        long target = (suggestion > 0)
                ? suggestion
                : Math.max(1, TARGET_SHARD_BYTES / pb);
        if (c > 1 && target * target > (long) c * z * z) {
            return new ShardShape(1, c, z);          // bundle channels: whole volume/t
        }
        int sz = (suggestion > 0) ? fitShardPlanes(z, suggestion)
                                  : autoShardCount(pb, z);
        return new ShardShape(1, 1, sz);
    }

    /** Total shard files across all series for a given set of plans. */
    static long totalShardFiles(ShardShape[] plans, int[] sizeC,
                                int[] sizeZ, int[] sizeT) {
        long files = 0;
        for (int i = 0; i < plans.length; i++) {
            ShardShape p = plans[i];
            long ft = ceilDiv(sizeT[i], p.t());
            long fc = ceilDiv(sizeC[i], p.c());
            long fz = ceilDiv(sizeZ[i], p.z());
            files += ft * fc * fz;
        }
        return files;
    }

    /**
     * Coarsen one doubling step: grow the shardable axis of each plan that has
     * room — T for a pure time series, Z for a Z-sharded plan.  Channel-bundled
     * shards already minimize to one file per timepoint and are left as-is.
     * Returns false when nothing can grow further.
     */
    private static boolean growShards(ShardShape[] plans, int[] sizeC,
                                      int[] sizeZ, int[] sizeT) {
        boolean grew = false;
        for (int i = 0; i < plans.length; i++) {
            ShardShape p = plans[i];
            if (sizeC[i] == 1 && sizeZ[i] == 1) {            // time series → grow T
                if (p.t() < sizeT[i]) {
                    plans[i] = new ShardShape(
                            Math.min(sizeT[i], p.t() * 2), 1, 1);
                    grew = true;
                }
            } else if (p.c() == 1 && p.t() == 1 && p.z() < sizeZ[i]) { // Z-sharded
                plans[i] = new ShardShape(1, 1, Math.min(sizeZ[i], p.z() * 2));
                grew = true;
            }
        }
        return grew;
    }

    private static long totalFiles(int[] shardZ, int[] sizeZ, long[] tc) {
        long files = 0;
        for (int i = 0; i < shardZ.length; i++) {
            int sz = Math.max(1, shardZ[i]);
            long blocks = (sizeZ[i] + sz - 1L) / sz;   // ceil(Z / shardZ)
            files += tc[i] * blocks;
        }
        return files;
    }

    private static boolean canGrow(int[] shardZ, int[] sizeZ) {
        for (int i = 0; i < shardZ.length; i++) {
            if (shardZ[i] < sizeZ[i]) return true;
        }
        return false;
    }

    @Override
    public void setSeries(int s) throws IOException {
        if (s < 0 || s >= series.size()) {
            throw new IOException("series " + s + " out of range (have "
                    + series.size() + ")");
        }
        current = s;
    }

    /**
     * Index-only writes are unsupported: an OME-Zarr array is addressed by
     * coordinate.  The export loop always calls the coordinate-aware overload;
     * this method refuses loudly rather than risk misplacing a plane.
     */
    @Override
    public void writePlane(int planeIndex, byte[] data) {
        throw new UnsupportedOperationException(
                "ZarrWriter addresses planes by coordinate; use "
                + "writePlane(planeIndex, c, z, t, data)");
    }

    @Override
    public void writePlane(int planeIndex, int c, int z, int t, byte[] data)
            throws IOException {
        writeShardBlock(t, c, z, 1, 1, 1, data);
    }

    @Override
    public int[] preferredBlockShape(int s) {
        if (s < 0 || s >= series.size()) return new int[]{1, 1, 1};
        ShardShape sh = series.get(s).shard();
        return new int[]{sh.t(), sh.c(), sh.z()};
    }

    /**
     * Write a shard-aligned {@code [bt,bc,bz,Y,X]} block in a single operation.
     * Safe to call concurrently for distinct blocks: each addresses a disjoint
     * region, and a block aligned to the shard grid maps to one shard file
     * (verified by ZarrConcurrencyTest).  {@code data} is in TCZYX C-order.  The
     * current series must stay fixed while concurrent blocks for it are in flight.
     */
    @Override
    public void writeShardBlock(int tStart, int cStart, int zStart,
                                int bt, int bc, int bz, byte[] data)
            throws IOException {
        if (current < 0) {
            throw new IllegalStateException("setSeries not called");
        }
        SeriesArray sa = series.get(current);
        long expected = (long) bt * bc * bz
                * sa.sizeY() * sa.sizeX() * sa.bytesPerSample();
        if (data.length != expected) {
            throw new IOException("shard block byte count " + data.length
                    + " does not match expected " + expected + " (" + bt + "×"
                    + bc + "×" + bz + " planes of " + sa.sizeX() + "x"
                    + sa.sizeY() + ")");
        }
        // Wrap the raw bytes (read in the source's byte order) as a typed
        // ma2 array; zarr-java re-encodes to the array's on-disk codec and,
        // when sharded, packs the plane-chunks into the shard file.
        ByteBuffer buf = ByteBuffer.wrap(data).order(sa.order());
        var block = ucar.ma2.Array.factory(sa.ma2Type(),
                new int[]{bt, bc, bz, sa.sizeY(), sa.sizeX()}, buf);
        try {
            sa.array().write(new long[]{tStart, cStart, zStart, 0, 0}, block);
        } catch (RuntimeException e) {
            throw new IOException("Failed to write shard block (t=" + tStart
                    + " c=" + cStart + " z=" + zStart + " ext=" + bt + "×" + bc
                    + "×" + bz + "): " + e.getMessage(), e);
        }
    }

    /** The store root, for cleanup of a partial store after a failed export. */
    public Path outputRoot() {
        return root;
    }

    @Override
    public long getBytesWritten() {
        if (root == null || !Files.exists(root)) return 0;
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); }
                        catch (IOException e) { return 0L; }
                    }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        // Each writeShardBlock writes its chunk/shard in full on the spot, so there
        // is nothing to flush or finalize at close for a single-level store.
    }

    // ================================================================
    // Codec + type + OME-XML helpers
    // ================================================================

    /**
     * Apply the configured codec to the chunk pipeline.  The codec string is
     * {@code name} or {@code name:level} (e.g. {@code "zstd"} or
     * {@code "zstd:1"}); when a level is present it is passed to the codec
     * (zstd keeps its corruption checksum on).
     *
     * <p><b>blosc</b> is configured as <b>lz4 + byte-shuffle</b> — its classic
     * fast identity.  The byte-shuffle filter (at the array element size, which
     * zarr-java auto-detects as the blosc typesize) reorders the high/low bytes
     * of multi-byte samples so numeric image data compresses better and faster;
     * lz4 is the fast inner compressor.  The level maps to blosc's clevel (0–9,
     * default 5).  Shuffle is always on: without it this codec would be
     * pointless (strictly worse than plain zstd).
     *
     * <p>Numeric range checking is the tool's responsibility (it reports an
     * out-of-range level as INVALID_ARGUMENT); this method applies the value
     * and lets zarr-java reject anything the tool let through.
     */
    private dev.zarr.zarrjava.v3.codec.CodecBuilder applyInnerCodec(
            dev.zarr.zarrjava.v3.codec.CodecBuilder cb) {
        // On-disk endianness is fixed little (independent of source order).
        // When sharded, this is the per-inner-chunk (per-plane) pipeline.
        cb = cb.withBytes("LITTLE");
        String name = codec;
        Integer level = null;
        int colon = codec.indexOf(':');
        if (colon >= 0) {
            name = codec.substring(0, colon);
            level = Integer.valueOf(codec.substring(colon + 1));
        }
        return switch (name) {
            case "none", "raw" -> cb;
            case "gzip"  -> level != null ? cb.withGzip(level) : cb.withGzip();
            case "zstd"  -> level != null ? cb.withZstd(level, true) : cb.withZstd();
            case "blosc" -> cb.withBlosc("lz4", "shuffle", level != null ? level : 5);
            default -> throw new IllegalArgumentException(
                    "unknown codec '" + name
                    + "' (expected none, gzip, zstd, or blosc)");
        };
    }

    private static DataType mapDataType(String omeType) {
        return switch (omeType == null ? "" : omeType.toLowerCase()) {
            case "uint8"  -> DataType.UINT8;
            case "int8"   -> DataType.INT8;
            case "uint16" -> DataType.UINT16;
            case "int16"  -> DataType.INT16;
            case "uint32" -> DataType.UINT32;
            case "int32"  -> DataType.INT32;
            case "float"  -> DataType.FLOAT32;
            case "double" -> DataType.FLOAT64;
            default -> throw new IllegalArgumentException(
                    "OME pixel type '" + omeType
                    + "' is not supported for OME-Zarr export");
        };
    }

    private static ByteOrder readByteOrder(Element pixels, int bytesPerSample) {
        String be = pixels.getAttribute("BigEndian");
        if (be == null || be.isBlank()) {
            if (bytesPerSample > 1) {
                throw new IllegalArgumentException(
                        "OME-XML Pixels is missing the BigEndian attribute; "
                        + "refusing to guess byte order for a multi-byte "
                        + "pixel type");
            }
            return ByteOrder.LITTLE_ENDIAN; // irrelevant for 1-byte samples
        }
        return Boolean.parseBoolean(be)
                ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    /** Build the NGFF 0.5 {@code ome} attribute for one image. */
    private static Map<String, Object> multiscales(
            String name, String spaceUnit,
            double scaleZ, double scaleY, double scaleX) {
        List<Map<String, Object>> axes = List.of(
                axis("t", "time", null),
                axis("c", "channel", null),
                axis("z", "space", spaceUnit),
                axis("y", "space", spaceUnit),
                axis("x", "space", spaceUnit));

        var scale = new LinkedHashMap<String, Object>();
        scale.put("type", "scale");
        scale.put("scale", List.of(1.0, 1.0, scaleZ, scaleY, scaleX));

        var dataset = new LinkedHashMap<String, Object>();
        dataset.put("path", "0");
        dataset.put("coordinateTransformations", List.of(scale));

        var multiscale = new LinkedHashMap<String, Object>();
        if (name != null) multiscale.put("name", name);   // standard NGFF image name
        multiscale.put("axes", axes);
        multiscale.put("datasets", List.of(dataset));

        var ome = new LinkedHashMap<String, Object>();
        ome.put("version", "0.5");
        ome.put("multiscales", List.of(multiscale));
        return ome;
    }

    private static Map<String, Object> axis(
            String name, String type, String unit) {
        var a = new LinkedHashMap<String, Object>();
        a.put("name", name);
        a.put("type", type);
        if (unit != null) a.put("unit", unit);
        return a;
    }

    /**
     * Build the NGFF {@code omero} block from the Pixels' {@code Channel}
     * elements, so channel names (and colors) are first-class in the Zarr
     * metadata rather than only in the OME-XML sidecar — the analogue of how we
     * surface physical units in the multiscales scale.
     *
     * <p>Returns {@code null} when the source carries no real channel metadata
     * (no names and no colors), so we never fabricate channel info that the
     * file did not actually contain.
     */
    private static Map<String, Object> buildOmero(
            Element pixels, DataType dataType, String name) {
        List<Element> chans = OmeXmlSurgery.getChildElements(pixels, null, "Channel");
        if (chans.isEmpty()) return null;

        boolean anyRealMetadata = false;
        long[] range = typeRange(dataType);   // null for float types
        var channels = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < chans.size(); i++) {
            Element ch = chans.get(i);
            String chName = ch.getAttribute("Name");
            String colorAttr = ch.getAttribute("Color");
            boolean hasName = chName != null && !chName.isBlank();
            String hex = (colorAttr == null || colorAttr.isBlank())
                    ? null : omeColorToHex(colorAttr);
            if (hasName || hex != null) anyRealMetadata = true;

            var cm = new LinkedHashMap<String, Object>();
            cm.put("label", hasName ? chName : "Channel " + i);
            if (hex != null) cm.put("color", hex);
            if (range != null) {
                var w = new LinkedHashMap<String, Object>();
                w.put("min", range[0]);
                w.put("max", range[1]);
                w.put("start", range[0]);
                w.put("end", range[1]);
                cm.put("window", w);   // full type range; neutral default contrast
            }
            cm.put("active", true);
            channels.add(cm);
        }
        if (!anyRealMetadata) return null;

        var rdefs = new LinkedHashMap<String, Object>();
        rdefs.put("defaultT", 0);
        rdefs.put("defaultZ", 0);
        rdefs.put("model", channels.size() > 1 ? "color" : "greyscale");

        var omero = new LinkedHashMap<String, Object>();
        if (name != null) omero.put("name", name);
        omero.put("channels", channels);
        omero.put("rdefs", rdefs);
        return omero;
    }

    /** The image's display name from the OME-XML {@code <Image Name>}, or null. */
    private static String imageName(Element pixels) {
        if (pixels.getParentNode() instanceof Element image) {
            String n = image.getAttribute("Name");
            if (n != null && !n.isBlank()) return n;
        }
        return null;
    }

    /** Inclusive display range for an integer pixel type; null for float types. */
    private static long[] typeRange(DataType dt) {
        if (dt == DataType.UINT8)  return new long[]{0, 255};
        if (dt == DataType.INT8)   return new long[]{-128, 127};
        if (dt == DataType.UINT16) return new long[]{0, 65535};
        if (dt == DataType.INT16)  return new long[]{-32768, 32767};
        if (dt == DataType.UINT32) return new long[]{0, 4294967295L};
        if (dt == DataType.INT32)  return new long[]{Integer.MIN_VALUE, Integer.MAX_VALUE};
        return null;   // FLOAT32/FLOAT64 have no fixed display range
    }

    /** OME signed-int RGBA color → NGFF "RRGGBB" hex; null if unparsable. */
    private static String omeColorToHex(String colorAttr) {
        try {
            long v = Long.parseLong(colorAttr.trim());
            int r = (int) ((v >> 24) & 0xFF);
            int g = (int) ((v >> 16) & 0xFF);
            int b = (int) ((v >> 8) & 0xFF);
            return String.format("%02X%02X%02X", r, g, b);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Map an OME length unit to an NGFF (UDUNITS) unit name, or null. */
    private static String ngffUnit(String omeUnit) {
        if (omeUnit == null || omeUnit.isBlank()) return "micrometer";
        return switch (omeUnit) {
            case "µm", "um", "micron", "micrometer" -> "micrometer";
            case "nm", "nanometer" -> "nanometer";
            case "mm", "millimeter" -> "millimeter";
            case "m", "meter" -> "meter";
            case "Å", "angstrom" -> "angstrom";
            default -> null;
        };
    }

    private static List<Element> parsePixels(String omeXml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(omeXml)));
        NodeList nodes = doc.getElementsByTagNameNS("*", "Pixels");
        var out = new ArrayList<Element>();
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private static int intAttr(Element e, String name) {
        String v = e.getAttribute(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(
                    "OME-XML Pixels missing required attribute " + name);
        }
        return Integer.parseInt(v);
    }

    private static double doubleAttr(Element e, String name, double dflt) {
        String v = e.getAttribute(name);
        if (v == null || v.isBlank()) return dflt;
        try { return Double.parseDouble(v); }
        catch (NumberFormatException ex) { return dflt; }
    }
}
