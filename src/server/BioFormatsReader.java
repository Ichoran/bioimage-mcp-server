package lab.kerrr.mcpbio.bioimageserver;

import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;

import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.xml.model.primitives.Color;
import ome.xml.model.primitives.Timestamp;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * {@link ImageReader} implementation backed by Bio-Formats.
 *
 * <p>All Bio-Formats API usage is confined to this class (and
 * {@link BioFormatsWriter}) so that the rest of the codebase
 * depends only on our model records and interfaces.
 */
public final class BioFormatsReader implements ImageReader {

    private loci.formats.ImageReader reader;
    private IMetadata meta;
    private OMEXMLService xmlService;
    private int originalMetadataCount;

    @Override
    public void open(Path path) throws IOException {
        try {
            var factory = new ServiceFactory();
            xmlService = factory.getInstance(OMEXMLService.class);
            meta = xmlService.createOMEXMLMetadata();
        } catch (DependencyException | ServiceException e) {
            throw new IOException("Failed to initialize OME-XML metadata service", e);
        }

        reader = new loci.formats.ImageReader();
        reader.setMetadataStore(meta);

        try {
            reader.setId(path.toAbsolutePath().toString());
        } catch (FormatException e) {
            throw new IOException("Bio-Formats cannot read file: " + path, e);
        }

        // Count global + per-series metadata entries
        Hashtable<String, Object> global = reader.getGlobalMetadata();
        originalMetadataCount = global != null ? global.size() : 0;
        for (int s = 0; s < reader.getSeriesCount(); s++) {
            reader.setSeries(s);
            Hashtable<String, Object> series = reader.getSeriesMetadata();
            if (series != null) {
                originalMetadataCount += series.size();
            }
        }
        // Reset to series 0
        if (reader.getSeriesCount() > 0) {
            reader.setSeries(0);
        }
    }

    @Override
    public int getSeriesCount() {
        checkOpen();
        return reader.getSeriesCount();
    }

    @Override
    public ImageMetadata getMetadata(int series, ImageMetadata.DetailLevel detailLevel) {
        checkOpen();
        int seriesCount = reader.getSeriesCount();
        checkSeries(series, seriesCount);

        // Build summaries for all series
        var summaries = new ArrayList<ImageMetadata.SeriesSummary>();
        for (int s = 0; s < seriesCount; s++) {
            reader.setSeries(s);
            summaries.add(new ImageMetadata.SeriesSummary(
                    s,
                    meta.getImageName(s),
                    reader.getSizeX(),
                    reader.getSizeY(),
                    reader.getSizeZ(),
                    reader.getSizeC(),
                    reader.getSizeT()));
        }

        // Build detailed info for requested series
        reader.setSeries(series);
        var seriesInfo = buildSeriesInfo(series, detailLevel);

        // Estimate omitted metadata bytes
        long omittedBytes = switch (detailLevel) {
            case FULL -> 0;
            case STANDARD -> estimateExtraMetadataBytes(series);
            case SUMMARY -> estimateStandardMetadataBytes(series)
                          + estimateExtraMetadataBytes(series);
        };

        return new ImageMetadata(
                reader.getFormat(),
                summaries,
                series,
                seriesInfo,
                detailLevel,
                omittedBytes);
    }

    @Override
    public boolean isLittleEndian(int series) {
        checkOpen();
        checkSeries(series, reader.getSeriesCount());
        reader.setSeries(series);
        return reader.isLittleEndian();
    }

    @Override
    public byte[] readPlane(int series, int channel, int z, int timepoint)
            throws IOException {
        checkOpen();
        checkSeries(series, reader.getSeriesCount());
        reader.setSeries(series);

        if (channel < 0 || channel >= reader.getSizeC())
            throw new IllegalArgumentException("channel out of range: " + channel);
        if (z < 0 || z >= reader.getSizeZ())
            throw new IllegalArgumentException("z out of range: " + z);
        if (timepoint < 0 || timepoint >= reader.getSizeT())
            throw new IllegalArgumentException("timepoint out of range: " + timepoint);

        int planeIndex = FormatTools.getIndex(reader, z, channel, timepoint);
        try {
            return reader.openBytes(planeIndex);
        } catch (FormatException e) {
            throw new IOException("Failed to read plane (series=" + series
                    + ", c=" + channel + ", z=" + z + ", t=" + timepoint + ")", e);
        }
    }

    @Override
    public String getOMEXML() {
        checkOpen();
        try {
            // Get a copy of the metadata and resolve references
            var omeMeta = xmlService.getOMEMetadata(
                    (loci.formats.meta.MetadataRetrieve) meta);
            omeMeta.resolveReferences();

            // Populate original metadata into OME-XML
            Hashtable<String, Object> global = reader.getGlobalMetadata();
            if (global != null && !global.isEmpty()) {
                xmlService.populateOriginalMetadata(omeMeta, global);
            }

            return xmlService.getOMEXML(omeMeta);
        } catch (ServiceException e) {
            // OME-XML generation failed — return null rather than crashing,
            // since getOMEXML is optional
            return null;
        }
    }

    @Override
    public int getOriginalMetadataCount() {
        checkOpen();
        return originalMetadataCount;
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
            reader = null;
        }
        meta = null;
        xmlService = null;
    }

    // ---- Metadata extraction ----

    private SeriesInfo buildSeriesInfo(int series, ImageMetadata.DetailLevel level) {
        reader.setSeries(series);

        // Always available: dimensions and pixel type
        int sizeX = reader.getSizeX();
        int sizeY = reader.getSizeY();
        int sizeZ = reader.getSizeZ();
        int sizeC = reader.getSizeC();
        int sizeT = reader.getSizeT();
        PixelType pixelType = mapPixelType(reader.getPixelType());
        String dimOrder = reader.getDimensionOrder();
        String name = meta.getImageName(series);

        // Physical pixel sizes (available at all levels)
        PixelSize physX = mapLength(meta.getPixelsPhysicalSizeX(series));
        PixelSize physY = mapLength(meta.getPixelsPhysicalSizeY(series));
        PixelSize physZ = mapLength(meta.getPixelsPhysicalSizeZ(series));

        // Channels
        List<ChannelInfo> channels = buildChannelInfo(series, sizeC, level);

        // Standard+ fields
        InstrumentInfo instrument = switch (level) {
            case SUMMARY -> null;
            case STANDARD, FULL -> buildInstrumentInfo(series);
        };

        String acquisitionDate = switch (level) {
            case SUMMARY -> null;
            case STANDARD, FULL -> {
                Timestamp ts = meta.getImageAcquisitionDate(series);
                yield ts != null ? ts.toString() : null;
            }
        };

        // Extra metadata (full only)
        Map<String, String> extra = switch (level) {
            case SUMMARY, STANDARD -> Map.of();
            case FULL -> buildExtraMetadata(series);
        };

        return new SeriesInfo(
                name, sizeX, sizeY, sizeZ, sizeC, sizeT,
                pixelType, dimOrder, physX, physY, physZ,
                channels, instrument, acquisitionDate, extra);
    }

    private List<ChannelInfo> buildChannelInfo(int series, int sizeC,
                                                ImageMetadata.DetailLevel level) {
        var channels = new ArrayList<ChannelInfo>();
        int metaChannelCount = meta.getChannelCount(series);

        for (int c = 0; c < sizeC; c++) {
            if (c >= metaChannelCount) {
                // No metadata for this channel
                channels.add(ChannelInfo.named(c, null));
                continue;
            }

            String name = meta.getChannelName(series, c);

            if (level == ImageMetadata.DetailLevel.SUMMARY) {
                channels.add(ChannelInfo.named(c, name));
                continue;
            }

            // Standard/Full: include wavelengths, fluor, color
            String fluor = meta.getChannelFluor(series, c);

            OptionalDouble excitation = OptionalDouble.empty();
            Length excLen = meta.getChannelExcitationWavelength(series, c);
            if (excLen != null) {
                excitation = OptionalDouble.of(
                        excLen.value(UNITS.NANOMETRE).doubleValue());
            }

            OptionalDouble emission = OptionalDouble.empty();
            Length emLen = meta.getChannelEmissionWavelength(series, c);
            if (emLen != null) {
                emission = OptionalDouble.of(
                        emLen.value(UNITS.NANOMETRE).doubleValue());
            }

            OptionalInt color = OptionalInt.empty();
            Color omeColor = meta.getChannelColor(series, c);
            if (omeColor != null) {
                // OME Color stores as RGBA; our model uses ARGB
                int argb = (omeColor.getAlpha() << 24)
                         | (omeColor.getRed()   << 16)
                         | (omeColor.getGreen() << 8)
                         | omeColor.getBlue();
                color = OptionalInt.of(argb);
            }

            channels.add(new ChannelInfo(c, name, fluor, excitation, emission, color));
        }
        return channels;
    }

    private InstrumentInfo buildInstrumentInfo(int series) {
        // Find the instrument and objective for this image
        String instrumentRef = null;
        try {
            instrumentRef = meta.getImageInstrumentRef(series);
        } catch (Exception e) {
            // No instrument reference — fine
        }

        if (instrumentRef == null) {
            return null;
        }

        // Find the instrument index
        int instrumentCount = meta.getInstrumentCount();
        int instrIdx = -1;
        for (int i = 0; i < instrumentCount; i++) {
            if (instrumentRef.equals(meta.getInstrumentID(i))) {
                instrIdx = i;
                break;
            }
        }
        if (instrIdx < 0) {
            return null;
        }

        // Find the objective used for this image
        String objectiveSettingsId = null;
        try {
            objectiveSettingsId = meta.getObjectiveSettingsID(series);
        } catch (Exception e) {
            // No objective settings — fine
        }

        if (objectiveSettingsId == null) {
            return null;
        }

        int objectiveCount = meta.getObjectiveCount(instrIdx);
        int objIdx = -1;
        for (int o = 0; o < objectiveCount; o++) {
            if (objectiveSettingsId.equals(
                    meta.getObjectiveID(instrIdx, o))) {
                objIdx = o;
                break;
            }
        }
        if (objIdx < 0) {
            return null;
        }

        // Extract objective fields — use final locals for lambda capture
        final int ii = instrIdx;
        final int oi = objIdx;
        String model = safeGet(() -> meta.getObjectiveModel(ii, oi));
        String manufacturer = safeGet(() -> meta.getObjectiveManufacturer(ii, oi));
        Double nominalMag = safeGet(() -> meta.getObjectiveNominalMagnification(ii, oi));
        Double calibratedMag = safeGet(() -> meta.getObjectiveCalibratedMagnification(ii, oi));
        Double na = safeGet(() -> meta.getObjectiveLensNA(ii, oi));

        String immersion = null;
        try {
            var imm = meta.getObjectiveImmersion(ii, oi);
            if (imm != null) immersion = imm.getValue();
        } catch (Exception e) { /* missing */ }

        String correction = null;
        try {
            var corr = meta.getObjectiveCorrection(ii, oi);
            if (corr != null) correction = corr.getValue();
        } catch (Exception e) { /* missing */ }

        var info = new InstrumentInfo(model, manufacturer, nominalMag,
                calibratedMag, na, immersion, correction);
        return info.isEmpty() ? null : info;
    }

    private Map<String, String> buildExtraMetadata(int series) {
        var extra = new LinkedHashMap<String, String>();

        // Global metadata
        Hashtable<String, Object> global = reader.getGlobalMetadata();
        if (global != null) {
            for (var entry : global.entrySet()) {
                extra.put(entry.getKey(),
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }

        // Series-specific metadata
        reader.setSeries(series);
        Hashtable<String, Object> seriesMeta = reader.getSeriesMetadata();
        if (seriesMeta != null) {
            for (var entry : seriesMeta.entrySet()) {
                extra.put(entry.getKey(),
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }

        return extra;
    }

    // ---- Omitted metadata estimation ----

    private long estimateExtraMetadataBytes(int series) {
        long bytes = 0;
        Hashtable<String, Object> global = reader.getGlobalMetadata();
        if (global != null) {
            for (var entry : global.entrySet()) {
                bytes += entry.getKey().length();
                if (entry.getValue() != null) {
                    bytes += entry.getValue().toString().length();
                }
            }
        }
        reader.setSeries(series);
        Hashtable<String, Object> seriesMeta = reader.getSeriesMetadata();
        if (seriesMeta != null) {
            for (var entry : seriesMeta.entrySet()) {
                bytes += entry.getKey().length();
                if (entry.getValue() != null) {
                    bytes += entry.getValue().toString().length();
                }
            }
        }
        return bytes;
    }

    private long estimateStandardMetadataBytes(int series) {
        long bytes = 0;
        // Instrument info
        InstrumentInfo instr = buildInstrumentInfo(series);
        if (instr != null && !instr.isEmpty()) {
            bytes += 200;
        }
        // Channel wavelengths
        int channelCount = meta.getChannelCount(series);
        for (int c = 0; c < channelCount; c++) {
            if (meta.getChannelExcitationWavelength(series, c) != null) bytes += 20;
            if (meta.getChannelEmissionWavelength(series, c) != null) bytes += 20;
            String fluor = meta.getChannelFluor(series, c);
            if (fluor != null) bytes += fluor.length();
        }
        // Acquisition date
        Timestamp ts = meta.getImageAcquisitionDate(series);
        if (ts != null) bytes += ts.toString().length();
        return bytes;
    }

    // ---- Type mapping ----

    private static PixelType mapPixelType(int bfType) {
        return switch (bfType) {
            case FormatTools.INT8   -> PixelType.INT8;
            case FormatTools.UINT8  -> PixelType.UINT8;
            case FormatTools.INT16  -> PixelType.INT16;
            case FormatTools.UINT16 -> PixelType.UINT16;
            case FormatTools.INT32  -> PixelType.INT32;
            case FormatTools.UINT32 -> PixelType.UINT32;
            case FormatTools.FLOAT  -> PixelType.FLOAT;
            case FormatTools.DOUBLE -> PixelType.DOUBLE;
            case FormatTools.BIT    -> PixelType.BIT;
            default -> throw new IllegalArgumentException(
                    "Unsupported Bio-Formats pixel type: " + bfType);
        };
    }

    private static PixelSize mapLength(Length length) {
        if (length == null) return null;
        double value = length.value().doubleValue();
        if (value <= 0 || !Double.isFinite(value)) return null;

        var unit = length.unit();
        PixelSize.LengthUnit mappedUnit;
        if (unit.equals(UNITS.NANOMETRE) || unit.equals(UNITS.NANOMETER)) {
            mappedUnit = PixelSize.LengthUnit.NANOMETER;
        } else if (unit.equals(UNITS.MICROMETRE) || unit.equals(UNITS.MICROMETER)
                   || unit.equals(UNITS.MICROM)) {
            mappedUnit = PixelSize.LengthUnit.MICROMETER;
        } else if (unit.equals(UNITS.MILLIMETRE) || unit.equals(UNITS.MILLIMETER)) {
            mappedUnit = PixelSize.LengthUnit.MILLIMETER;
        } else if (unit.equals(UNITS.CENTIMETRE) || unit.equals(UNITS.CENTIMETER)) {
            mappedUnit = PixelSize.LengthUnit.CENTIMETER;
        } else if (unit.equals(UNITS.METRE) || unit.equals(UNITS.METER)) {
            mappedUnit = PixelSize.LengthUnit.METER;
        } else {
            // Convert to micrometers (most common in microscopy)
            double inMicrons = length.value(UNITS.MICROMETRE).doubleValue();
            if (inMicrons <= 0 || !Double.isFinite(inMicrons)) return null;
            return PixelSize.of(inMicrons, PixelSize.LengthUnit.MICROMETER);
        }
        return new PixelSize(BigDecimal.valueOf(value), mappedUnit);
    }

    // ---- Utilities ----

    private void checkOpen() {
        if (reader == null) throw new IllegalStateException("reader is not open");
    }

    private static void checkSeries(int series, int count) {
        if (series < 0 || series >= count) {
            throw new IllegalArgumentException(
                    "series index " + series + " out of range [0, " + count + ")");
        }
    }

    /**
     * Safely call a metadata getter that may throw if the data is absent.
     * Bio-Formats metadata methods sometimes throw NullPointerException or
     * IndexOutOfBoundsException when metadata is not present, rather than
     * returning null.
     */
    private static <T> T safeGet(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }
}
