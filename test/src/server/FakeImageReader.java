package lab.kerrr.mcpbio.bioimageserver;

import org.apache.commons.text.StringEscapeUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A synthetic {@link ImageReader} for testing tool logic without
 * Bio-Formats or real microscopy files.
 *
 * <p>Configured via a builder that defines one or more series, each
 * with its own dimensions, pixel type, and metadata.  Pixel data is
 * generated synthetically: each pixel value is a deterministic
 * function of its (x, y, channel, z, timepoint) coordinates, so
 * tests can compute expected values independently.
 *
 * <h3>Pixel generation</h3>
 * For integer types, the value at position (x, y) in plane
 * (c, z, t) is:
 * <pre>
 *     raw = (y * sizeX + x + c * 7 + z * 13 + t * 31) mod (maxVal + 1)
 * </pre>
 * where {@code maxVal} is the pixel type's unsigned range.  This
 * produces a position-dependent gradient that wraps around, making
 * it easy to predict values in tests.
 *
 * <p>For floating-point types, the value is the raw integer formula
 * above, cast to float/double (no modular wrapping).
 */
public final class FakeImageReader implements ImageReader {

    private final String formatName;
    private final List<FakeSeries> seriesList;
    private final boolean littleEndian;
    private final Map<String, String> originalMetadata;
    private boolean open;
    private Path openedPath;

    private FakeImageReader(String formatName, List<FakeSeries> seriesList,
                            boolean littleEndian,
                            Map<String, String> originalMetadata) {
        this.formatName = formatName;
        this.seriesList = List.copyOf(seriesList);
        this.littleEndian = littleEndian;
        this.originalMetadata = Map.copyOf(originalMetadata);
    }

    // ---- Builder ----

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String formatName = "Fake Format";
        private final List<FakeSeries> series = new ArrayList<>();
        private boolean littleEndian = true;
        private final Map<String, String> originalMetadata = new LinkedHashMap<>();

        public Builder formatName(String name) { this.formatName = name; return this; }
        public Builder littleEndian(boolean le) { this.littleEndian = le; return this; }

        public Builder addSeries(FakeSeries s) { series.add(s); return this; }

        /**
         * Add flat original metadata entries (format-specific key-value
         * pairs).  These appear both in {@link #getOriginalMetadataCount()}
         * and as OriginalMetadataAnnotation entries in {@link #getOMEXML()}.
         */
        public Builder originalMetadata(Map<String, String> meta) {
            this.originalMetadata.putAll(meta);
            return this;
        }

        public FakeImageReader build() {
            if (series.isEmpty()) {
                throw new IllegalStateException("must add at least one series");
            }
            return new FakeImageReader(formatName, series, littleEndian,
                                       originalMetadata);
        }
    }

    // ---- FakeSeries record ----

    /**
     * Configuration for one synthetic series.
     */
    public record FakeSeries(
            String name,
            int sizeX, int sizeY, int sizeZ, int sizeC, int sizeT,
            PixelType pixelType,
            String dimensionOrder,
            PixelSize physicalSizeX,
            PixelSize physicalSizeY,
            PixelSize physicalSizeZ,
            List<ChannelInfo> channels,
            InstrumentInfo instrument,
            String acquisitionDate,
            Map<String, String> extraMetadata) {

        /** Convenience: minimal series with just dimensions and pixel type. */
        public static FakeSeries simple(int sizeX, int sizeY, int sizeZ,
                                        int sizeC, int sizeT, PixelType pixelType) {
            return new FakeSeries(null, sizeX, sizeY, sizeZ, sizeC, sizeT,
                    pixelType, "XYCZT", null, null, null,
                    List.of(), null, null, Map.of());
        }
    }

    // ---- ImageReader implementation ----

    @Override
    public void open(Path path) throws IOException {
        // Accept any path — we generate data, not read files.
        this.openedPath = path;
        this.open = true;
    }

    @Override
    public int getSeriesCount() {
        checkOpen();
        return seriesList.size();
    }

    @Override
    public ImageMetadata getMetadata(int series, ImageMetadata.DetailLevel detailLevel) {
        checkOpen();
        checkSeries(series);

        var allSummaries = new ArrayList<ImageMetadata.SeriesSummary>();
        for (int i = 0; i < seriesList.size(); i++) {
            var s = seriesList.get(i);
            allSummaries.add(new ImageMetadata.SeriesSummary(
                    i, s.name(), s.sizeX(), s.sizeY(), s.sizeZ(), s.sizeC(), s.sizeT()));
        }

        var fs = seriesList.get(series);
        var seriesInfo = buildSeriesInfo(fs, detailLevel);

        long omittedBytes = switch (detailLevel) {
            case FULL -> 0;
            case STANDARD -> estimateExtraMetadataBytes(fs);
            case SUMMARY -> estimateStandardMetadataBytes(fs)
                          + estimateExtraMetadataBytes(fs);
        };

        return new ImageMetadata(formatName, allSummaries, series,
                seriesInfo, detailLevel, omittedBytes);
    }

    @Override
    public boolean isLittleEndian(int series) {
        checkOpen();
        checkSeries(series);
        return littleEndian;
    }

    @Override
    public byte[] readPlane(int series, int channel, int z, int timepoint)
            throws IOException {
        checkOpen();
        checkSeries(series);
        var fs = seriesList.get(series);
        if (channel < 0 || channel >= fs.sizeC())
            throw new IllegalArgumentException("channel out of range: " + channel);
        if (z < 0 || z >= fs.sizeZ())
            throw new IllegalArgumentException("z out of range: " + z);
        if (timepoint < 0 || timepoint >= fs.sizeT())
            throw new IllegalArgumentException("timepoint out of range: " + timepoint);

        return generatePlane(fs, channel, z, timepoint);
    }

    @Override
    public void close() throws IOException {
        open = false;
        openedPath = null;
    }

    /** Returns the path passed to {@link #open}, for test assertions. */
    public Path openedPath() { return openedPath; }

    /** Returns whether the reader is currently open. */
    public boolean isOpen() { return open; }

    @Override
    public String getOMEXML() {
        checkOpen();
        return generateOMEXML();
    }

    @Override
    public int getOriginalMetadataCount() {
        checkOpen();
        return originalMetadata.size();
    }

    // ---- OME-XML generation ----

    private String generateOMEXML() {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<OME xmlns=\"http://www.openmicroscopy.org/Schemas/OME/2016-06\">\n");

        for (int s = 0; s < seriesList.size(); s++) {
            var fs = seriesList.get(s);
            String seriesName = fs.name() != null ? fs.name() : "Series " + s;
            sb.append("  <Image ID=\"Image:").append(s)
              .append("\" Name=\"").append(StringEscapeUtils.escapeXml10(seriesName)).append("\">\n");
            sb.append("    <Pixels ID=\"Pixels:").append(s)
              .append("\" DimensionOrder=\"").append(fs.dimensionOrder())
              .append("\" SizeX=\"").append(fs.sizeX())
              .append("\" SizeY=\"").append(fs.sizeY())
              .append("\" SizeZ=\"").append(fs.sizeZ())
              .append("\" SizeC=\"").append(fs.sizeC())
              .append("\" SizeT=\"").append(fs.sizeT())
              .append("\" Type=\"").append(omePixelType(fs.pixelType()))
              .append("\" BigEndian=\"").append(!littleEndian)
              .append("\">\n");

            // Channel elements
            for (int c = 0; c < fs.sizeC(); c++) {
                String chName = c < fs.channels().size() && fs.channels().get(c).name() != null
                        ? fs.channels().get(c).name()
                        : "Channel " + c;
                sb.append("      <Channel ID=\"Channel:").append(s).append(':').append(c)
                  .append("\" Name=\"").append(StringEscapeUtils.escapeXml10(chName)).append("\"");
                if (c < fs.channels().size() && fs.channels().get(c).color().isPresent()) {
                    sb.append(" Color=\"").append(fs.channels().get(c).color().getAsInt()).append("\"");
                }
                sb.append("/>\n");
            }

            // TiffData elements (one per plane, XYCZT order)
            int planeIndex = 0;
            for (int t = 0; t < fs.sizeT(); t++) {
                for (int z = 0; z < fs.sizeZ(); z++) {
                    for (int c = 0; c < fs.sizeC(); c++) {
                        sb.append("      <TiffData FirstC=\"").append(c)
                          .append("\" FirstZ=\"").append(z)
                          .append("\" FirstT=\"").append(t)
                          .append("\" PlaneCount=\"1\" IFD=\"")
                          .append(planeIndex++).append("\"/>\n");
                    }
                }
            }

            // Plane elements
            for (int t = 0; t < fs.sizeT(); t++) {
                for (int z = 0; z < fs.sizeZ(); z++) {
                    for (int c = 0; c < fs.sizeC(); c++) {
                        sb.append("      <Plane TheC=\"").append(c)
                          .append("\" TheZ=\"").append(z)
                          .append("\" TheT=\"").append(t).append("\"/>\n");
                    }
                }
            }

            sb.append("    </Pixels>\n");
            sb.append("  </Image>\n");
        }

        // OriginalMetadataAnnotation entries
        if (!originalMetadata.isEmpty()) {
            sb.append("  <StructuredAnnotations>\n");
            int annIdx = 0;
            for (var entry : originalMetadata.entrySet()) {
                sb.append("    <XMLAnnotation ID=\"Annotation:OriginalMetadata:")
                  .append(annIdx++)
                  .append("\" Namespace=\"openmicroscopy.org/OriginalMetadata\">\n");
                sb.append("      <Value>\n");
                sb.append("        <OriginalMetadata>\n");
                sb.append("          <Key>").append(StringEscapeUtils.escapeXml10(entry.getKey()))
                  .append("</Key>\n");
                sb.append("          <Value>").append(StringEscapeUtils.escapeXml10(entry.getValue()))
                  .append("</Value>\n");
                sb.append("        </OriginalMetadata>\n");
                sb.append("      </Value>\n");
                sb.append("    </XMLAnnotation>\n");
            }
            sb.append("  </StructuredAnnotations>\n");
        }

        sb.append("</OME>\n");
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

    // ---- Pixel generation ----

    /**
     * Compute the synthetic pixel value at a given position.
     * Public so tests can independently compute expected values.
     */
    public static long syntheticValue(int x, int y, int sizeX,
                                      int channel, int z, int timepoint) {
        return (long) y * sizeX + x + channel * 7L + z * 13L + timepoint * 31L;
    }

    private byte[] generatePlane(FakeSeries fs, int c, int z, int t) {
        int sizeX = fs.sizeX();
        int sizeY = fs.sizeY();
        int bpp = fs.pixelType().bytesPerPixel();
        var buf = ByteBuffer.allocate(sizeX * sizeY * bpp);
        buf.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                long raw = syntheticValue(x, y, sizeX, c, z, t);
                switch (fs.pixelType()) {
                    case BIT, UINT8 -> buf.put((byte) (raw & 0xFF));
                    case INT8       -> buf.put((byte) (raw & 0xFF));
                    case UINT16     -> buf.putShort((short) (raw & 0xFFFF));
                    case INT16      -> buf.putShort((short) (raw & 0xFFFF));
                    case UINT32     -> buf.putInt((int) (raw & 0xFFFF_FFFFL));
                    case INT32      -> buf.putInt((int) (raw & 0xFFFF_FFFFL));
                    case FLOAT      -> buf.putFloat((float) raw);
                    case DOUBLE     -> buf.putDouble((double) raw);
                }
            }
        }
        return buf.array();
    }

    // ---- Metadata construction ----

    private SeriesInfo buildSeriesInfo(FakeSeries fs, ImageMetadata.DetailLevel level) {
        // Summary: dimensions, pixel type, physical sizes, channel names
        // Standard: + channels with wavelengths, instrument, acquisition date
        // Full: + extra metadata
        List<ChannelInfo> channels = switch (level) {
            case SUMMARY -> {
                // At summary level, include channels with names only
                var named = new ArrayList<ChannelInfo>();
                for (int i = 0; i < fs.sizeC(); i++) {
                    var orig = i < fs.channels().size() ? fs.channels().get(i) : null;
                    named.add(ChannelInfo.named(i, orig != null ? orig.name() : null));
                }
                yield named;
            }
            case STANDARD, FULL -> {
                if (fs.channels().isEmpty()) {
                    // Generate default channel entries
                    var defaults = new ArrayList<ChannelInfo>();
                    for (int i = 0; i < fs.sizeC(); i++) {
                        defaults.add(ChannelInfo.named(i, null));
                    }
                    yield defaults;
                }
                yield fs.channels();
            }
        };

        InstrumentInfo instrument = switch (level) {
            case SUMMARY -> null;
            case STANDARD, FULL -> fs.instrument();
        };

        String acquisitionDate = switch (level) {
            case SUMMARY -> null;
            case STANDARD, FULL -> fs.acquisitionDate();
        };

        Map<String, String> extra = switch (level) {
            case SUMMARY, STANDARD -> Map.of();
            case FULL -> fs.extraMetadata();
        };

        return new SeriesInfo(
                fs.name(),
                fs.sizeX(), fs.sizeY(), fs.sizeZ(), fs.sizeC(), fs.sizeT(),
                fs.pixelType(), fs.dimensionOrder(),
                fs.physicalSizeX(), fs.physicalSizeY(), fs.physicalSizeZ(),
                channels, instrument, acquisitionDate, extra);
    }

    private long estimateExtraMetadataBytes(FakeSeries fs) {
        long bytes = 0;
        for (var entry : fs.extraMetadata().entrySet()) {
            bytes += entry.getKey().length() + entry.getValue().length();
        }
        return bytes;
    }

    private long estimateStandardMetadataBytes(FakeSeries fs) {
        long bytes = 0;
        // Instrument info
        if (fs.instrument() != null && !fs.instrument().isEmpty()) {
            bytes += 200; // rough estimate
        }
        // Channel wavelengths
        for (var ch : fs.channels()) {
            if (ch.excitationWavelength().isPresent()) bytes += 20;
            if (ch.emissionWavelength().isPresent()) bytes += 20;
            if (ch.fluor() != null) bytes += ch.fluor().length();
        }
        // Acquisition date
        if (fs.acquisitionDate() != null) bytes += fs.acquisitionDate().length();
        return bytes;
    }

    // ---- Validation ----

    private void checkOpen() {
        if (!open) throw new IllegalStateException("reader is not open");
    }

    private void checkSeries(int series) {
        if (series < 0 || series >= seriesList.size()) {
            throw new IllegalArgumentException(
                    "series index " + series + " out of range [0, " + seriesList.size() + ")");
        }
    }
}
