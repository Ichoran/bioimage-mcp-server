package lab.kerrr.mcpbio.bioimageserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private boolean open;
    private Path openedPath;

    private FakeImageReader(String formatName, List<FakeSeries> seriesList,
                            boolean littleEndian) {
        this.formatName = formatName;
        this.seriesList = List.copyOf(seriesList);
        this.littleEndian = littleEndian;
    }

    // ---- Builder ----

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String formatName = "Fake Format";
        private final List<FakeSeries> series = new ArrayList<>();
        private boolean littleEndian = true;

        public Builder formatName(String name) { this.formatName = name; return this; }
        public Builder littleEndian(boolean le) { this.littleEndian = le; return this; }

        public Builder addSeries(FakeSeries s) { series.add(s); return this; }

        public FakeImageReader build() {
            if (series.isEmpty()) {
                throw new IllegalStateException("must add at least one series");
            }
            return new FakeImageReader(formatName, series, littleEndian);
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
