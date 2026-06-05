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

    /** One exported series: its open array plus what writeBlock needs. */
    private record SeriesArray(
            Array array, int sizeY, int sizeX, int sizeZ, int shardZ,
            ucar.ma2.DataType ma2Type, int bytesPerSample, ByteOrder order) {}

    private Path root;
    private String codec;
    private final List<SeriesArray> series = new ArrayList<>();
    private volatile int current = -1;

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

            // Compute the shard depth (Z-planes per file) for every series up
            // front, so the global file-count cap can see all series together.
            int n = images.size();
            long[] planeBytes = new long[n];
            int[] sizeZ = new int[n];
            long[] tc = new long[n];
            for (int s = 0; s < n; s++) {
                Element px = images.get(s);
                planeBytes[s] = (long) intAttr(px, "SizeX") * intAttr(px, "SizeY")
                        * mapDataType(px.getAttribute("Type")).getByteCount();
                sizeZ[s] = intAttr(px, "SizeZ");
                tc[s] = (long) intAttr(px, "SizeT") * intAttr(px, "SizeC");
            }
            int[] shardZ = computeShardDepths(planeBytes, sizeZ, tc, MAX_FILES);

            for (int s = 0; s < n; s++) {
                series.add(createSeries(store, s, images.get(s), shardZ[s]));
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
                                     int shardZ) throws Exception {
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
        // plane decompressed per access).  When shardZ > 1 we bundle shardZ
        // plane-chunks into one shard file, so the file count stays sane for
        // volumetric/timeseries data without changing the read granularity.
        var b = Array.metadataBuilder()
                .withShape(sizeT, sizeC, sizeZ, sizeY, sizeX)
                .withDataType(dataType)
                .withFillValue(0)
                .withDimensionNames("t", "c", "z", "y", "x");
        if (shardZ > 1) {
            b = b.withChunkShape(1, 1, shardZ, sizeY, sizeX)   // shard = file
                 .withCodecs(cb -> cb.withSharding(
                         new int[]{1, 1, 1, sizeY, sizeX},      // inner = plane
                         this::applyInnerCodec));
        } else {
            b = b.withChunkShape(1, 1, 1, sizeY, sizeX)
                 .withCodecs(this::applyInnerCodec);
        }
        Array array = Array.create(store.resolve(String.valueOf(s), "0"), b.build());

        return new SeriesArray(
                array, sizeY, sizeX, sizeZ, shardZ, dataType.getMA2DataType(),
                bytesPerSample, order);
    }

    // ================================================================
    // Sharding policy (file count vs. write-buffer size) — see PLAN Phase 16.
    // ================================================================

    /**
     * Choose the shard depth (Z-planes per file) for each series.
     *
     * <p>Per series: a plane already over the ~1 MB sweet spot stays one file
     * per plane; a volume under 4 MB goes in a single file; otherwise shardZ is
     * the smallest power of two whose block reaches ~1 MB.  Then, globally, if
     * the total chunk-file count would exceed {@code maxFiles}, shard depths are
     * doubled (never past a whole volume) until the count drops under the cap.
     */
    static int[] computeShardDepths(long[] planeBytes, int[] sizeZ,
                                    long[] tc, int maxFiles) {
        int n = planeBytes.length;
        int[] shardZ = new int[n];
        for (int i = 0; i < n; i++) {
            long pb = planeBytes[i];
            int z = sizeZ[i];
            if (pb > TARGET_SHARD_BYTES) {
                shardZ[i] = 1;
            } else if ((long) z * pb < SMALL_VOLUME_BYTES) {
                shardZ[i] = z;
            } else {
                int s = 1;
                while ((long) s * pb < TARGET_SHARD_BYTES && s < z) s <<= 1;
                shardZ[i] = Math.min(s, z);
            }
            if (shardZ[i] < 1) shardZ[i] = 1;
        }
        while (totalFiles(shardZ, sizeZ, tc) > maxFiles && canGrow(shardZ, sizeZ)) {
            for (int i = 0; i < n; i++) {
                if (shardZ[i] < sizeZ[i]) {
                    shardZ[i] = Math.min(sizeZ[i], shardZ[i] * 2);
                }
            }
        }
        return shardZ;
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
        writeBlock(planeIndex, c, z, t, 1, data);
    }

    @Override
    public int preferredBlockDepth(int s) {
        return (s >= 0 && s < series.size()) ? series.get(s).shardZ() : 1;
    }

    /**
     * Write {@code blockZ} contiguous Z-planes (one channel/timepoint) in a
     * single operation.  Safe to call concurrently for distinct blocks: each
     * block addresses a disjoint region, and a block aligned to the shard grid
     * maps to one shard file (verified by ZarrConcurrencyTest).  The current
     * series must stay fixed while concurrent blocks for it are in flight.
     */
    @Override
    public void writeBlock(int planeIndex, int c, int zStart, int t,
                           int blockZ, byte[] data) throws IOException {
        if (current < 0) {
            throw new IllegalStateException("setSeries not called");
        }
        SeriesArray sa = series.get(current);
        long expected = (long) blockZ * sa.sizeY * sa.sizeX * sa.bytesPerSample;
        if (data.length != expected) {
            throw new IOException("block byte count " + data.length
                    + " does not match expected " + expected + " (" + blockZ
                    + " planes of " + sa.sizeX + "x" + sa.sizeY + ")");
        }
        // Wrap the raw bytes (read in the source's byte order) as a typed
        // ma2 array; zarr-java re-encodes to the array's on-disk codec and,
        // when sharded, packs the plane-chunks into the shard file.
        ByteBuffer buf = ByteBuffer.wrap(data).order(sa.order);
        var block = ucar.ma2.Array.factory(
                sa.ma2Type, new int[]{1, 1, blockZ, sa.sizeY, sa.sizeX}, buf);
        try {
            sa.array.write(new long[]{t, c, zStart, 0, 0}, block);
        } catch (RuntimeException e) {
            throw new IOException("Failed to write block (c=" + c + " zStart="
                    + zStart + " t=" + t + " blockZ=" + blockZ + "): "
                    + e.getMessage(), e);
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
        // Each writeBlock writes its chunk/shard in full on the spot, so there
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
