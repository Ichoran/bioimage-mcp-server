package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Exports image data to OME-TIFF, with optional subsetting and
 * metadata control.
 *
 * <p>Reads planes from an {@link ImageReader} and writes them to an
 * {@link ImageWriter}, passing the OME-XML through with surgical
 * modifications for any subsetting.  Reports what was written and
 * what metadata was preserved or lost.
 */
public final class ExportToTiffTool {

    private ExportToTiffTool() {}

    /** Compression options for the output file. */
    public enum Compression {
        NONE("Uncompressed"),
        LZW("LZW"),
        ZLIB("zlib");

        private final String bioFormatsName;
        Compression(String bioFormatsName) {
            this.bioFormatsName = bioFormatsName;
        }
        /** The string Bio-Formats writers expect. */
        public String bioFormatsName() { return bioFormatsName; }
    }

    /**
     * Controls how much metadata is preserved in the output.
     *
     * <ul>
     *   <li>{@code ALL} — pass through the full OME-XML including all
     *       OriginalMetadataAnnotation entries.  Safest for fidelity.
     *   <li>{@code STRUCTURED} — strip OriginalMetadataAnnotation
     *       entries but keep all OME schema elements.  Smaller output.
     *   <li>{@code MINIMAL} — keep only essential elements (Pixels,
     *       Channel, TiffData).  Smallest output.
     * </ul>
     */
    public enum MetadataMode { ALL, STRUCTURED, MINIMAL }

    /**
     * Parameters for an {@code export_to_tiff} call.
     *
     * @param inputPath     path to the source image file
     * @param outputPath    path for the output OME-TIFF file
     * @param series        series to export (null = all)
     * @param channels      channel indices to include (null = all)
     * @param zStart        first Z-slice (inclusive, null = 0)
     * @param zEnd          last Z-slice (inclusive, null = sizeZ-1)
     * @param tStart        first timepoint (inclusive, null = 0)
     * @param tEnd          last timepoint (inclusive, null = sizeT-1)
     * @param compression   output compression
     * @param metadataMode  how much metadata to preserve
     * @param timeout       wall-clock time limit
     * @param maxBytes      approximate cap on raw pixel bytes to
     *                      read+write
     */
    public record Request(
            String inputPath,
            String outputPath,
            Integer series,
            int[] channels,
            Integer zStart,
            Integer zEnd,
            Integer tStart,
            Integer tEnd,
            Compression compression,
            MetadataMode metadataMode,
            Duration timeout,
            long maxBytes) {

        public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
        public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024;

        public Request {
            if (inputPath == null || inputPath.isBlank()) {
                throw new IllegalArgumentException(
                        "inputPath must not be blank");
            }
            if (outputPath == null || outputPath.isBlank()) {
                throw new IllegalArgumentException(
                        "outputPath must not be blank");
            }
            if (series != null && series < 0) {
                throw new IllegalArgumentException(
                        "series must be non-negative");
            }
            if (channels != null) {
                if (channels.length == 0) {
                    throw new IllegalArgumentException(
                            "channels array must not be empty");
                }
                for (int ch : channels) {
                    if (ch < 0) {
                        throw new IllegalArgumentException(
                                "channel indices must be non-negative");
                    }
                }
            }
            if (zStart != null && zStart < 0) {
                throw new IllegalArgumentException(
                        "zStart must be non-negative");
            }
            if (zEnd != null && zEnd < 0) {
                throw new IllegalArgumentException(
                        "zEnd must be non-negative");
            }
            if (zStart != null && zEnd != null && zEnd < zStart) {
                throw new IllegalArgumentException(
                        "zEnd must be >= zStart");
            }
            if (tStart != null && tStart < 0) {
                throw new IllegalArgumentException(
                        "tStart must be non-negative");
            }
            if (tEnd != null && tEnd < 0) {
                throw new IllegalArgumentException(
                        "tEnd must be non-negative");
            }
            if (tStart != null && tEnd != null && tEnd < tStart) {
                throw new IllegalArgumentException(
                        "tEnd must be >= tStart");
            }
            if (compression == null) {
                throw new IllegalArgumentException(
                        "compression must not be null");
            }
            if (metadataMode == null) {
                throw new IllegalArgumentException(
                        "metadataMode must not be null");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException(
                        "timeout must be positive");
            }
            if (maxBytes <= 0) {
                throw new IllegalArgumentException(
                        "maxBytes must be positive");
            }
            channels = channels != null ? channels.clone() : null;
        }

        /** Full factory with nullable defaults. */
        public static Request of(String inputPath, String outputPath,
                                  Integer series, int[] channels,
                                  Integer zStart, Integer zEnd,
                                  Integer tStart, Integer tEnd,
                                  Compression compression,
                                  MetadataMode metadataMode,
                                  Duration timeout, Long maxBytes) {
            return new Request(
                    inputPath, outputPath,
                    series, channels,
                    zStart, zEnd, tStart, tEnd,
                    compression != null ? compression : Compression.NONE,
                    metadataMode != null ? metadataMode : MetadataMode.ALL,
                    timeout != null ? timeout : DEFAULT_TIMEOUT,
                    maxBytes != null ? maxBytes : DEFAULT_MAX_BYTES);
        }

        /** Minimal: export everything with defaults. */
        public static Request of(String inputPath, String outputPath) {
            return of(inputPath, outputPath,
                    null, null, null, null, null, null,
                    null, null, null, null);
        }
    }

    /**
     * Summary of what was exported and what metadata was preserved.
     *
     * @param outputPath              the written file path
     * @param bytesWritten            approximate bytes written
     * @param seriesExported          number of series written
     * @param channelsPerSeries       channels per series in output
     * @param zSlicesPerSeries        Z-slices per series in output
     * @param timepointsPerSeries     timepoints per series in output
     * @param totalPlanesWritten      total planes across all series
     * @param compression             compression used
     * @param metadataMode            metadata mode used
     * @param sourceFormat            the source format name
     * @param omeXmlPresent           whether OME-XML was available
     *                                from the reader
     * @param originalMetadataInXml   count of OriginalMetadataAnnotation
     *                                entries in the written XML
     * @param originalMetadataInReader count of flat metadata entries
     *                                the reader knew about
     * @param warnings                any warnings about metadata loss
     *                                or format conversion
     */
    public record ExportResult(
            String outputPath,
            long bytesWritten,
            int seriesExported,
            int channelsPerSeries,
            int zSlicesPerSeries,
            int timepointsPerSeries,
            long totalPlanesWritten,
            Compression compression,
            MetadataMode metadataMode,
            String sourceFormat,
            boolean omeXmlPresent,
            int originalMetadataInXml,
            int originalMetadataInReader,
            List<String> warnings) {

        public ExportResult {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Execute the export_to_tiff tool.
     *
     * <p>Convenience overload that uses a single validator for both
     * input and output paths.
     */
    public static ToolResult<ExportResult> execute(
            Request request,
            PathValidator pathValidator,
            Supplier<ImageReader> readerFactory,
            Supplier<ImageWriter> writerFactory) {
        return execute(request, pathValidator, pathValidator,
                readerFactory, writerFactory);
    }

    /**
     * Execute the export_to_tiff tool.
     *
     * @param request             the export parameters
     * @param inputPathValidator   validates the input (source) path
     * @param outputPathValidator  validates the output (destination) path;
     *                            may differ from the input validator because
     *                            the output file does not yet exist
     * @param readerFactory       creates a new (unopened) reader
     * @param writerFactory       creates a new (unopened) writer
     * @return a structured result or a failure
     */
    public static ToolResult<ExportResult> execute(
            Request request,
            PathValidator inputPathValidator,
            PathValidator outputPathValidator,
            Supplier<ImageReader> readerFactory,
            Supplier<ImageWriter> writerFactory) {

        // 1. Validate input path
        var inputAccess = inputPathValidator.check(request.inputPath());
        if (inputAccess instanceof AccessResult.Denied denied) {
            return ToolResult.accessDenied(
                    "input: " + denied.reason());
        }
        var inputPath = ((AccessResult.Allowed) inputAccess).canonicalPath();

        // 2. Validate output path
        var outputAccess = outputPathValidator.check(request.outputPath());
        if (outputAccess instanceof AccessResult.Denied denied) {
            return ToolResult.accessDenied(
                    "output: " + denied.reason());
        }
        var outputPath = ((AccessResult.Allowed) outputAccess).canonicalPath();

        // 3. Input and output must be different files
        if (inputPath.equals(outputPath)) {
            return ToolResult.invalidArgument(
                    "input and output paths must be different");
        }

        // 4. Run with timeout
        var task = new CancellableTask(request.timeout());
        var result = task.run(() -> {
            try (var reader = readerFactory.get()) {
                reader.open(inputPath);

                // Determine which series to export
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
                    for (int i = 0; i < seriesCount; i++) {
                        seriesToExport[i] = i;
                    }
                }

                // Get metadata for the first series to export
                // (used for resolving defaults and budget checks)
                var meta = reader.getMetadata(
                        seriesToExport[0], DetailLevel.STANDARD);
                var si = meta.detailedSeries();

                // Resolve ranges
                int zStart = request.zStart() != null
                        ? request.zStart() : 0;
                int zEnd = request.zEnd() != null
                        ? request.zEnd() : si.sizeZ() - 1;
                int tStart = request.tStart() != null
                        ? request.tStart() : 0;
                int tEnd = request.tEnd() != null
                        ? request.tEnd() : si.sizeT() - 1;

                // Validate ranges
                if (zEnd >= si.sizeZ()) {
                    throw new IllegalArgumentException(
                            "zEnd " + zEnd + " out of range, series has "
                            + si.sizeZ() + " Z-slice(s)");
                }
                if (tEnd >= si.sizeT()) {
                    throw new IllegalArgumentException(
                            "tEnd " + tEnd + " out of range, series has "
                            + si.sizeT() + " timepoint(s)");
                }

                // Resolve channels
                int[] channels;
                if (request.channels() != null) {
                    for (int ch : request.channels()) {
                        if (ch >= si.sizeC()) {
                            throw new IllegalArgumentException(
                                    "channel " + ch + " out of range,"
                                    + " series has " + si.sizeC()
                                    + " channel(s)");
                        }
                    }
                    channels = request.channels();
                } else {
                    channels = new int[si.sizeC()];
                    for (int i = 0; i < si.sizeC(); i++) {
                        channels[i] = i;
                    }
                }

                // Check byte budget
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
                            + " bytes of pixel data, which exceeds the"
                            + " maxBytes budget (" + request.maxBytes()
                            + "). Try subsetting channels, Z, or T.");
                }

                // Get OME-XML and apply surgery
                String omeXml = reader.getOMEXML();
                boolean omeXmlPresent = omeXml != null;
                int origMetaInReader = reader.getOriginalMetadataCount();
                int origMetaInXml = 0;
                var warnings = new ArrayList<String>();

                if (omeXml != null) {
                    // Subset the XML
                    var spec = new OmeXmlSurgery.SubsetSpec(
                            request.series(), channels,
                            zStart, zEnd, tStart, tEnd);
                    var surgeryResult = OmeXmlSurgery.subset(omeXml, spec);
                    omeXml = surgeryResult.xml();

                    // Apply metadata mode
                    switch (request.metadataMode()) {
                        case ALL -> {
                            origMetaInXml = surgeryResult.originalMetadataKept();
                        }
                        case STRUCTURED -> {
                            int before = surgeryResult.originalMetadataKept();
                            omeXml = OmeXmlSurgery.stripOriginalMetadata(omeXml);
                            origMetaInXml = 0;
                            if (before > 0) {
                                warnings.add(before
                                        + " OriginalMetadataAnnotation"
                                        + " entries stripped (metadata"
                                        + " mode: STRUCTURED).");
                            }
                        }
                        case MINIMAL -> {
                            int before = surgeryResult.originalMetadataKept();
                            omeXml = OmeXmlSurgery.stripToMinimal(omeXml);
                            origMetaInXml = 0;
                            if (before > 0) {
                                warnings.add(before
                                        + " OriginalMetadataAnnotation"
                                        + " entries stripped (metadata"
                                        + " mode: MINIMAL).");
                            }
                            warnings.add("Metadata stripped to minimal"
                                    + " (Pixels, Channel, TiffData only).");
                        }
                    }

                    // Warn about flat metadata not in XML
                    if (origMetaInReader > 0
                            && origMetaInReader > origMetaInXml) {
                        int lost = origMetaInReader - origMetaInXml;
                        warnings.add("The reader reports "
                                + origMetaInReader + " flat metadata"
                                + " entries from the source format, but"
                                + " only " + origMetaInXml + " are"
                                + " included as OriginalMetadata"
                                + " annotations in the OME-XML. "
                                + lost + " entries may not survive"
                                + " format conversion.");
                    }
                } else {
                    // No OME-XML available — build minimal XML
                    // from our model records
                    warnings.add("No OME-XML available from the"
                            + " reader. A minimal OME-XML header will"
                            + " be generated from the image dimensions"
                            + " and pixel type.");
                    omeXml = buildMinimalOmeXml(
                            si, channels, zStart, zEnd, tStart, tEnd);
                }

                // Warn about proprietary format conversion
                String formatName = meta.formatName();
                if (!formatName.toLowerCase().contains("ome-tiff")
                        && !formatName.toLowerCase().contains("ome tiff")) {
                    warnings.add("Source format: " + formatName
                            + ". Some format-specific metadata may not"
                            + " be captured by Bio-Formats and will not"
                            + " be present in the OME-TIFF output.");
                }

                // Write
                try (var writer = writerFactory.get()) {
                    writer.open(outputPath,
                            omeXml, request.compression().bioFormatsName());

                    long planesWritten = 0;
                    for (int seriesIdx = 0;
                         seriesIdx < seriesToExport.length;
                         seriesIdx++) {
                        int s = seriesToExport[seriesIdx];
                        writer.setSeries(seriesIdx);

                        int planeIdx = 0;
                        for (int t = tStart; t <= tEnd; t++) {
                            for (int z = zStart; z <= zEnd; z++) {
                                for (int ch : channels) {
                                    byte[] data = reader.readPlane(
                                            s, ch, z, t);
                                    writer.writePlane(planeIdx++, data);
                                    planesWritten++;
                                }
                            }
                        }
                    }

                    return new ExportResult(
                            outputPath.toString(),
                            writer.getBytesWritten(),
                            seriesToExport.length,
                            channels.length,
                            zCount,
                            tCount,
                            planesWritten,
                            request.compression(),
                            request.metadataMode(),
                            formatName,
                            omeXmlPresent,
                            origMetaInXml,
                            origMetaInReader,
                            warnings);
                }
            }
        });

        return ToolResult.unwrap(result);
    }

    // ================================================================
    // Minimal OME-XML generation (fallback when reader has none)
    // ================================================================

    /**
     * Build a minimal OME-XML string from our model records.  Used
     * only when the reader doesn't provide OME-XML.
     */
    static String buildMinimalOmeXml(
            SeriesInfo si, int[] channels,
            int zStart, int zEnd, int tStart, int tEnd) {
        int zCount = zEnd - zStart + 1;
        int tCount = tEnd - tStart + 1;
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<OME xmlns=\"http://www.openmicroscopy.org/Schemas/OME/2016-06\">\n");
        sb.append("  <Image ID=\"Image:0\" Name=\"export\">\n");
        sb.append("    <Pixels ID=\"Pixels:0\"");
        sb.append(" DimensionOrder=\"XYCZT\"");
        sb.append(" SizeX=\"").append(si.sizeX()).append("\"");
        sb.append(" SizeY=\"").append(si.sizeY()).append("\"");
        sb.append(" SizeZ=\"").append(zCount).append("\"");
        sb.append(" SizeC=\"").append(channels.length).append("\"");
        sb.append(" SizeT=\"").append(tCount).append("\"");
        sb.append(" Type=\"").append(omePixelType(si.pixelType())).append("\"");
        sb.append(">\n");

        for (int i = 0; i < channels.length; i++) {
            sb.append("      <Channel ID=\"Channel:0:").append(i).append("\"/>\n");
        }

        int planeIdx = 0;
        for (int t = 0; t < tCount; t++) {
            for (int z = 0; z < zCount; z++) {
                for (int c = 0; c < channels.length; c++) {
                    sb.append("      <TiffData FirstC=\"").append(c)
                      .append("\" FirstZ=\"").append(z)
                      .append("\" FirstT=\"").append(t)
                      .append("\" PlaneCount=\"1\" IFD=\"")
                      .append(planeIdx++).append("\"/>\n");
                }
            }
        }

        sb.append("    </Pixels>\n");
        sb.append("  </Image>\n");
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

}
