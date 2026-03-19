package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

/**
 * Reads a microscopy file and returns structured metadata.
 *
 * <p>This is the simplest tool — metadata only, no pixel data.  It
 * validates the path, opens a reader with timeout protection, extracts
 * metadata, and caps the response size if it would exceed the budget.
 *
 * <p>The tool never throws — it returns a {@link ToolResult} that
 * is either a {@link ToolResult.Success} or a {@link ToolResult.Failure}
 * with a structured error.
 */
public final class InspectImageTool {

    private InspectImageTool() {}

    /**
     * Parameters for an {@code inspect_image} call.
     *
     * @param path             path to the image file
     * @param series           zero-based series index
     * @param detailLevel      how much metadata to return
     * @param timeout          wall-clock time limit for the entire operation
     * @param maxResponseBytes approximate cap on response size; if exceeded
     *                         the tool downgrades the detail level
     */
    public record Request(
            String path,
            int series,
            DetailLevel detailLevel,
            Duration timeout,
            long maxResponseBytes) {

        /** Sensible defaults: series 0, standard detail, 30 s, 64 KB. */
        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
        public static final long DEFAULT_MAX_RESPONSE_BYTES = 64 * 1024;

        public Request {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("path must not be blank");
            }
            if (series < 0) {
                throw new IllegalArgumentException(
                        "series must be non-negative, got: " + series);
            }
            if (detailLevel == null) {
                throw new IllegalArgumentException("detailLevel must not be null");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maxResponseBytes <= 0) {
                throw new IllegalArgumentException(
                        "maxResponseBytes must be positive");
            }
        }

        /** Request with defaults for unspecified optional fields. */
        public static Request of(String path, Integer series,
                                 DetailLevel detailLevel,
                                 Duration timeout, Long maxResponseBytes) {
            return new Request(
                    path,
                    series != null ? series : 0,
                    detailLevel != null ? detailLevel : DetailLevel.STANDARD,
                    timeout != null ? timeout : DEFAULT_TIMEOUT,
                    maxResponseBytes != null ? maxResponseBytes
                                            : DEFAULT_MAX_RESPONSE_BYTES);
        }

        /** Minimal request with all defaults. */
        public static Request of(String path) {
            return of(path, null, null, null, null);
        }
    }

    /**
     * Execute the inspect_image tool.
     *
     * @param request       the inspection parameters
     * @param pathValidator validates whether the path is accessible
     * @param readerFactory creates a new (unopened) reader instance
     * @return a structured result — either metadata or a failure
     */
    public static ToolResult<ImageMetadata> execute(
            Request request,
            PathValidator pathValidator,
            Supplier<ImageReader> readerFactory) {

        // 1. Validate path
        var access = pathValidator.check(request.path());
        if (access instanceof AccessResult.Denied denied) {
            return ToolResult.accessDenied(denied.reason());
        }
        var canonicalPath = ((AccessResult.Allowed) access).canonicalPath();

        // 2. Run with timeout: open reader, validate series, get metadata
        var task = new CancellableTask(request.timeout());
        var result = task.run(() -> {
            try (var reader = readerFactory.get()) {
                reader.open(canonicalPath);

                int seriesCount = reader.getSeriesCount();
                if (request.series() >= seriesCount) {
                    throw new IllegalArgumentException(
                            "series index " + request.series()
                            + " out of range, file has " + seriesCount
                            + " series (0-indexed)");
                }

                var metadata = reader.getMetadata(
                        request.series(), request.detailLevel());
                return capResponseSize(metadata, request.maxResponseBytes());
            }
        });

        // 3. Convert CancellableTask.Result → ToolResult
        return ToolResult.unwrap(result);
    }

    // ---- Response size capping ----

    /**
     * If the metadata exceeds the byte budget, downgrade detail level.
     * Within FULL, truncate extraMetadata entries first before downgrading.
     */
    static ImageMetadata capResponseSize(ImageMetadata metadata,
                                         long maxBytes) {
        long estimated = estimateSize(metadata);
        if (estimated <= maxBytes) {
            return metadata;
        }

        // If FULL, try truncating extraMetadata first
        if (metadata.detailLevel() == DetailLevel.FULL) {
            var truncated = truncateExtraMetadata(metadata, maxBytes);
            if (estimateSize(truncated) <= maxBytes) {
                return truncated;
            }
            // Still too big — downgrade to STANDARD
            return downgrade(metadata, DetailLevel.STANDARD, maxBytes);
        }

        // If STANDARD, downgrade to SUMMARY
        if (metadata.detailLevel() == DetailLevel.STANDARD) {
            return downgrade(metadata, DetailLevel.SUMMARY, maxBytes);
        }

        // SUMMARY is always returned even if over budget — dimensions
        // are essential.
        return metadata;
    }

    private static ImageMetadata downgrade(ImageMetadata original,
                                           DetailLevel targetLevel,
                                           long maxBytes) {
        var series = original.detailedSeries();

        // Rebuild SeriesInfo at the lower detail level
        var channels = switch (targetLevel) {
            case SUMMARY -> series.channels().stream()
                    .map(ch -> ChannelInfo.named(ch.index(), ch.name()))
                    .toList();
            case STANDARD, FULL -> series.channels();
        };

        var instrument = switch (targetLevel) {
            case SUMMARY -> null;
            case STANDARD, FULL -> series.instrument();
        };

        var acquisitionDate = switch (targetLevel) {
            case SUMMARY -> null;
            case STANDARD, FULL -> series.acquisitionDate();
        };

        var extra = switch (targetLevel) {
            case SUMMARY, STANDARD -> java.util.Map.<String, String>of();
            case FULL -> series.extraMetadata();
        };

        var downgradedSeries = new SeriesInfo(
                series.name(),
                series.sizeX(), series.sizeY(), series.sizeZ(),
                series.sizeC(), series.sizeT(),
                series.pixelType(), series.dimensionOrder(),
                targetLevel == DetailLevel.SUMMARY ? null : series.physicalSizeX(),
                targetLevel == DetailLevel.SUMMARY ? null : series.physicalSizeY(),
                targetLevel == DetailLevel.SUMMARY ? null : series.physicalSizeZ(),
                channels, instrument, acquisitionDate, extra);

        long omitted = original.omittedMetadataBytes()
                     + estimateSize(original) - estimateDowngradedSize(
                             original, downgradedSeries);

        return new ImageMetadata(
                original.formatName(), original.allSeries(),
                original.detailedSeriesIndex(), downgradedSeries,
                targetLevel, omitted);
    }

    private static ImageMetadata truncateExtraMetadata(
            ImageMetadata metadata, long maxBytes) {
        var series = metadata.detailedSeries();
        var extra = series.extraMetadata();
        if (extra.isEmpty()) return metadata;

        // Drop entries from the end until we're under budget.
        var kept = new LinkedHashMap<String, String>();
        long droppedBytes = 0;
        long currentEstimate = estimateSize(metadata);

        for (var entry : extra.entrySet()) {
            long entryBytes = entry.getKey().length()
                            + entry.getValue().length() + 8;
            if (currentEstimate - droppedBytes - entryBytes <= maxBytes
                    || kept.isEmpty()) {
                // Keep at least... actually, don't keep any if even one is too much.
                // We're over budget. Keep entries until adding one more would exceed.
                // Actually let's approach from the other direction: add entries
                // until budget is exceeded.
            }
        }

        // Simpler approach: add entries until budget is exceeded
        kept.clear();
        droppedBytes = 0;
        long baseSize = estimateSizeWithoutExtra(metadata);
        long extraBudget = maxBytes - baseSize;

        long usedExtra = 0;
        for (var entry : extra.entrySet()) {
            long entryBytes = entry.getKey().length()
                            + entry.getValue().length() + 8;
            if (usedExtra + entryBytes <= extraBudget) {
                kept.put(entry.getKey(), entry.getValue());
                usedExtra += entryBytes;
            } else {
                droppedBytes += entryBytes;
            }
        }

        var truncatedSeries = new SeriesInfo(
                series.name(),
                series.sizeX(), series.sizeY(), series.sizeZ(),
                series.sizeC(), series.sizeT(),
                series.pixelType(), series.dimensionOrder(),
                series.physicalSizeX(), series.physicalSizeY(),
                series.physicalSizeZ(),
                series.channels(), series.instrument(),
                series.acquisitionDate(), kept);

        return new ImageMetadata(
                metadata.formatName(), metadata.allSeries(),
                metadata.detailedSeriesIndex(), truncatedSeries,
                metadata.detailLevel(),
                metadata.omittedMetadataBytes() + droppedBytes);
    }

    // ---- Size estimation ----

    /** Rough estimate of the serialized metadata size in bytes. */
    static long estimateSize(ImageMetadata metadata) {
        return estimateSizeWithoutExtra(metadata)
             + estimateExtraSize(metadata.detailedSeries().extraMetadata());
    }

    private static long estimateSizeWithoutExtra(ImageMetadata metadata) {
        long size = 0;

        // Format name + overhead
        size += metadata.formatName().length() + 50;

        // All-series summaries
        for (var s : metadata.allSeries()) {
            size += 80;  // fixed fields
            if (s.name() != null) size += s.name().length();
        }

        // Detailed series
        var si = metadata.detailedSeries();
        size += 120;  // dimension numbers, pixel type, dimension order
        if (si.name() != null) size += si.name().length();

        // Physical sizes
        if (si.physicalSizeX() != null) size += 40;
        if (si.physicalSizeY() != null) size += 40;
        if (si.physicalSizeZ() != null) size += 40;

        // Channels
        for (var ch : si.channels()) {
            size += 60;  // index, wavelengths, color
            if (ch.name() != null) size += ch.name().length();
            if (ch.fluor() != null) size += ch.fluor().length();
        }

        // Instrument
        if (si.instrument() != null && !si.instrument().isEmpty()) {
            size += 100;
            var inst = si.instrument();
            if (inst.objectiveModel() != null) size += inst.objectiveModel().length();
            if (inst.manufacturer() != null) size += inst.manufacturer().length();
            if (inst.immersion() != null) size += inst.immersion().length();
            if (inst.correction() != null) size += inst.correction().length();
        }

        // Acquisition date
        if (si.acquisitionDate() != null) size += si.acquisitionDate().length() + 20;

        return size;
    }

    private static long estimateExtraSize(java.util.Map<String, String> extra) {
        long size = 0;
        for (var entry : extra.entrySet()) {
            size += entry.getKey().length() + entry.getValue().length() + 8;
        }
        return size;
    }

    private static long estimateDowngradedSize(ImageMetadata original,
                                                SeriesInfo downgraded) {
        // Build a temporary ImageMetadata with the downgraded series
        // to get a consistent estimate.  omittedMetadataBytes doesn't
        // matter here — we're just measuring the payload size.
        var temp = new ImageMetadata(
                original.formatName(), original.allSeries(),
                original.detailedSeriesIndex(), downgraded,
                original.detailLevel(), 0);
        return estimateSize(temp);
    }

}
