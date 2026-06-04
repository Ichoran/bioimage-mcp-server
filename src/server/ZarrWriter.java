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

    /** One exported series: its open array plus what writePlane needs. */
    private record SeriesArray(
            Array array, int sizeY, int sizeX,
            ucar.ma2.DataType ma2Type, int bytesPerSample, ByteOrder order) {}

    private Path root;
    private String codec;
    private final List<SeriesArray> series = new ArrayList<>();
    private int current = -1;

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
            for (int s = 0; s < images.size(); s++) {
                series.add(createSeries(store, s, images.get(s)));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(
                    "Failed to initialize OME-Zarr store: " + e.getMessage(), e);
        }
    }

    /** Build one series' image group + resolution-level array. */
    private SeriesArray createSeries(FilesystemStore store, int s, Element pixels)
            throws Exception {
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

        // Image group with NGFF 0.5 multiscales metadata.
        var imageAttrs = new Attributes();
        imageAttrs.set("ome", multiscales(spaceUnit, scaleZ, scaleY, scaleX));
        StoreHandle imageHandle = store.resolve(String.valueOf(s));
        dev.zarr.zarrjava.v3.Group.create(imageHandle, imageAttrs);

        // The single resolution-level array, chunked one plane deep with XY
        // tiling so reads/writes stay bounded for large planes.
        int chunkY = Math.min(sizeY, 1024);
        int chunkX = Math.min(sizeX, 1024);
        var meta = Array.metadataBuilder()
                .withShape(sizeT, sizeC, sizeZ, sizeY, sizeX)
                .withDataType(dataType)
                .withChunkShape(1, 1, 1, chunkY, chunkX)
                .withFillValue(0)
                .withDimensionNames("t", "c", "z", "y", "x")
                .withCodecs(this::codecs)
                .build();
        Array array = Array.create(store.resolve(String.valueOf(s), "0"), meta);

        return new SeriesArray(
                array, sizeY, sizeX, dataType.getMA2DataType(),
                bytesPerSample, order);
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
        if (current < 0) {
            throw new IllegalStateException("setSeries not called");
        }
        SeriesArray sa = series.get(current);
        long expected = (long) sa.sizeY * sa.sizeX * sa.bytesPerSample;
        if (data.length != expected) {
            throw new IOException("plane byte count " + data.length
                    + " does not match expected " + expected
                    + " for a " + sa.sizeX + "x" + sa.sizeY + " plane");
        }
        // Wrap the raw bytes (read in the source's byte order) as a typed
        // ma2 array; zarr-java re-encodes to the array's on-disk codec.
        ByteBuffer buf = ByteBuffer.wrap(data).order(sa.order);
        var plane = ucar.ma2.Array.factory(
                sa.ma2Type, new int[]{1, 1, 1, sa.sizeY, sa.sizeX}, buf);
        try {
            sa.array.write(new long[]{t, c, z, 0, 0}, plane);
        } catch (RuntimeException e) {
            throw new IOException("Failed to write plane (c=" + c + " z=" + z
                    + " t=" + t + "): " + e.getMessage(), e);
        }
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
        // zarr-java flushes each chunk on write(); nothing to finalize for a
        // single-level, unsharded store.
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
    private dev.zarr.zarrjava.v3.codec.CodecBuilder codecs(
            dev.zarr.zarrjava.v3.codec.CodecBuilder cb) {
        // On-disk endianness is fixed little (independent of source order).
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
            String spaceUnit, double scaleZ, double scaleY, double scaleX) {
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
