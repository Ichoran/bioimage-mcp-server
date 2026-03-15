package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.CancellableTask.Result;
import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * Generates a thumbnail preview of microscopy image data as an RGB PNG.
 *
 * <p>Supports Z-projection (mid-slice, max-intensity, sum), multi-channel
 * compositing with per-channel colors, and area-average downsampling.
 * Each channel is auto-contrasted independently before compositing.
 */
public final class GetThumbnailTool {

    private GetThumbnailTool() {}

    /** Default percentile for the auto-contrast black point. */
    static final double LOW_PERCENTILE = 0.1;
    /** Default percentile for the auto-contrast white point. */
    static final double HIGH_PERCENTILE = 99.9;

    /** Z-projection modes. */
    public enum Projection {
        /** Use only the middle Z-slice. */
        MID_SLICE,
        /** Take the maximum value across all Z-slices at each pixel. */
        MAX_INTENSITY,
        /** Sum values across all Z-slices at each pixel. */
        SUM
    }

    /**
     * Default channel colors: green for 1 channel, cyan/magenta for 2,
     * cyan/magenta/yellow for 3, and a rotating palette for more.
     *
     * <p>Colors are packed as 0x00RRGGBB (alpha ignored for compositing).
     */
    static final int[] DEFAULT_COLORS = {
        0x00FF00,   // green
        0xFF00FF,   // magenta
        0xFFFF00,   // yellow
        0x00FFFF,   // cyan
        0xFF8000,   // orange
        0x8080FF,   // light blue
        0xFF0000,   // red
        0x0080FF,   // blue
    };

    /**
     * Returns the default color for the given channel index and total
     * channel count.  Special-cases 1-channel (green) and 2-channel
     * (cyan/magenta); otherwise cycles through the palette.
     */
    static int defaultColor(int channelIndex, int totalChannels) {
        if (totalChannels == 1) {
            return 0x00FF00; // green
        }
        if (totalChannels == 2) {
            return channelIndex == 0 ? 0x00FFFF : 0xFF00FF; // cyan, magenta
        }
        // 3+: cyan, magenta, yellow, then cycle through full palette
        if (totalChannels >= 3 && channelIndex < 3) {
            return new int[] { 0x00FFFF, 0xFF00FF, 0xFFFF00 }[channelIndex];
        }
        return DEFAULT_COLORS[channelIndex % DEFAULT_COLORS.length];
    }

    /**
     * Parameters for a {@code get_thumbnail} call.
     *
     * @param path        path to the image file
     * @param series      zero-based series index
     * @param projection  how to handle Z-stacks
     * @param channels    which channels to include (null = all)
     * @param timepoint   zero-based timepoint index
     * @param maxSize     maximum dimension in pixels for the output
     * @param timeout     wall-clock time limit
     * @param maxBytes    approximate cap on raw pixel bytes to read
     */
    public record Request(
            String path,
            int series,
            Projection projection,
            int[] channels,
            int timepoint,
            int maxSize,
            Duration timeout,
            long maxBytes) {

        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
        public static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;
        public static final int DEFAULT_MAX_SIZE = 1024;

        public Request {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("path must not be blank");
            }
            if (series < 0) {
                throw new IllegalArgumentException("series must be non-negative");
            }
            if (projection == null) {
                throw new IllegalArgumentException("projection must not be null");
            }
            if (channels != null) {
                for (int ch : channels) {
                    if (ch < 0) {
                        throw new IllegalArgumentException(
                                "channel indices must be non-negative");
                    }
                }
                if (channels.length == 0) {
                    throw new IllegalArgumentException(
                            "channels array must not be empty");
                }
            }
            if (timepoint < 0) {
                throw new IllegalArgumentException(
                        "timepoint must be non-negative");
            }
            if (maxSize < 1) {
                throw new IllegalArgumentException("maxSize must be positive");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive");
            }
            channels = channels != null ? channels.clone() : null;
        }

        public static Request of(String path, Integer series,
                                   Projection projection, int[] channels,
                                   Integer timepoint, Integer maxSize,
                                   Duration timeout, Long maxBytes) {
            return new Request(
                    path,
                    series != null ? series : 0,
                    projection != null ? projection : Projection.MAX_INTENSITY,
                    channels,
                    timepoint != null ? timepoint : 0,
                    maxSize != null ? maxSize : DEFAULT_MAX_SIZE,
                    timeout != null ? timeout : DEFAULT_TIMEOUT,
                    maxBytes != null ? maxBytes : DEFAULT_MAX_BYTES);
        }

        /** Minimal request with all defaults. */
        public static Request of(String path) {
            return of(path, null, null, null, null, null, null, null);
        }
    }

    /**
     * Execute the get_thumbnail tool.
     *
     * @param request       the thumbnail parameters
     * @param pathValidator  validates whether the path is accessible
     * @param readerFactory  creates a new (unopened) reader instance
     * @return a structured result containing PNG bytes or a failure
     */
    public static ToolResult<byte[]> execute(
            Request request,
            PathValidator pathValidator,
            Supplier<ImageReader> readerFactory) {

        // 1. Validate path
        var access = pathValidator.check(request.path());
        if (access instanceof AccessResult.Denied denied) {
            return ToolResult.accessDenied(denied.reason());
        }
        var canonicalPath = ((AccessResult.Allowed) access).canonicalPath();

        // 2. Run with timeout
        var task = new CancellableTask(request.timeout());
        var result = task.run(() -> {
            try (var reader = readerFactory.get()) {
                reader.open(canonicalPath);

                // Get metadata (STANDARD to get channel colors)
                var meta = reader.getMetadata(
                        request.series(), DetailLevel.STANDARD);
                var si = meta.detailedSeries();

                // Resolve channels
                int[] channelIndices = resolveChannels(
                        request.channels(), si);

                // Validate timepoint
                if (request.timepoint() >= si.sizeT()) {
                    throw new IllegalArgumentException(
                            "timepoint " + request.timepoint()
                            + " out of range, series has " + si.sizeT()
                            + " timepoint(s)");
                }

                // Resolve Z slices to read based on projection mode
                int[] zSlices = resolveZSlices(request.projection(), si);

                // Check byte budget
                long bytesPerPlane = (long) si.sizeX() * si.sizeY()
                        * si.pixelType().bytesPerPixel();
                long totalBytes = bytesPerPlane * channelIndices.length
                        * zSlices.length;
                if (totalBytes > request.maxBytes()) {
                    throw new IllegalArgumentException(
                            "thumbnail requires " + totalBytes
                            + " bytes of pixel data, which exceeds the"
                            + " maxBytes budget (" + request.maxBytes()
                            + "). Try reducing channels, using mid_slice"
                            + " projection, or increasing the budget.");
                }

                ByteOrder order = reader.isLittleEndian(request.series())
                        ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

                // For each channel: read planes, project, auto-contrast
                int nPixels = si.sizeX() * si.sizeY();
                byte[][] channelUint8 = new byte[channelIndices.length][];
                int[] channelColors = new int[channelIndices.length];

                for (int i = 0; i < channelIndices.length; i++) {
                    int ch = channelIndices[i];

                    // Project across Z
                    double[] projected = projectZ(
                            reader, request.series(), ch,
                            request.timepoint(), zSlices,
                            si, order, request.projection());

                    // Auto-contrast
                    channelUint8[i] = PixelConverter.toUint8AutoContrast(
                            projected, LOW_PERCENTILE, HIGH_PERCENTILE);

                    // Resolve color
                    channelColors[i] = resolveColor(
                            ch, si.channels(), channelIndices.length);
                }

                // Composite channels into RGB
                int[] rgb = composite(
                        channelUint8, channelColors, nPixels);

                // Determine output dimensions
                int outWidth = si.sizeX();
                int outHeight = si.sizeY();
                int maxDim = Math.max(outWidth, outHeight);
                if (maxDim > request.maxSize()) {
                    double scale = (double) request.maxSize() / maxDim;
                    outWidth = Math.max(1,
                            (int) Math.round(outWidth * scale));
                    outHeight = Math.max(1,
                            (int) Math.round(outHeight * scale));
                }

                // Encode as RGB PNG
                return encodeRgbPng(rgb, si.sizeX(), si.sizeY(),
                                    outWidth, outHeight);
            }
        });

        return unwrap(result);
    }

    // ================================================================
    // Z-projection
    // ================================================================

    /**
     * Determine which Z-slices to read for the given projection mode.
     */
    static int[] resolveZSlices(Projection projection, SeriesInfo si) {
        return switch (projection) {
            case MID_SLICE -> new int[] { si.sizeZ() / 2 };
            case MAX_INTENSITY, SUM -> {
                int[] all = new int[si.sizeZ()];
                for (int i = 0; i < si.sizeZ(); i++) all[i] = i;
                yield all;
            }
        };
    }

    /**
     * Read planes for a single channel across Z-slices and combine them
     * according to the projection mode.  Returns double[] pixel values.
     */
    static double[] projectZ(
            ImageReader reader, int series, int channel, int timepoint,
            int[] zSlices, SeriesInfo si, ByteOrder order,
            Projection projection) throws IOException {

        int nPixels = si.sizeX() * si.sizeY();

        if (zSlices.length == 1) {
            // Single slice — just read and convert
            byte[] raw = reader.readPlane(
                    series, channel, zSlices[0], timepoint);
            return PixelConverter.toDoubles(raw, si.pixelType(), order);
        }

        // Multi-slice projection
        double[] result = null;
        for (int z : zSlices) {
            byte[] raw = reader.readPlane(series, channel, z, timepoint);
            double[] values = PixelConverter.toDoubles(
                    raw, si.pixelType(), order);

            if (result == null) {
                result = values;
            } else {
                switch (projection) {
                    case MAX_INTENSITY -> {
                        for (int i = 0; i < nPixels; i++) {
                            if (values[i] > result[i]) {
                                result[i] = values[i];
                            }
                        }
                    }
                    case SUM -> {
                        for (int i = 0; i < nPixels; i++) {
                            result[i] += values[i];
                        }
                    }
                    case MID_SLICE ->
                        throw new AssertionError("unreachable");
                }
            }
        }
        return result;
    }

    // ================================================================
    // Channel color resolution
    // ================================================================

    /**
     * Get the display color for a channel.  Uses the channel's metadata
     * color if available, otherwise falls back to defaults.
     */
    static int resolveColor(int channelIndex, List<ChannelInfo> channels,
                             int totalChannels) {
        if (channelIndex < channels.size()) {
            var info = channels.get(channelIndex);
            if (info.color().isPresent()) {
                // OME color is ARGB; extract RGB
                int argb = info.color().getAsInt();
                return argb & 0x00FFFFFF;
            }
        }
        return defaultColor(channelIndex, totalChannels);
    }

    // ================================================================
    // Channel compositing
    // ================================================================

    /**
     * Composite multiple auto-contrasted channels into an RGB image.
     *
     * <p>Each channel's uint8 intensity is scaled by its color and
     * accumulated additively.  The result is clamped to 0–255 per
     * component.
     *
     * @param channelUint8  per-channel uint8 intensities
     * @param channelColors per-channel RGB colors (0x00RRGGBB)
     * @param nPixels       number of pixels per channel
     * @return packed RGB array (index = y * width + x), each element
     *         is 0x00RRGGBB
     */
    static int[] composite(byte[][] channelUint8, int[] channelColors,
                            int nPixels) {
        // Accumulate in int to avoid overflow during addition
        int[] rSum = new int[nPixels];
        int[] gSum = new int[nPixels];
        int[] bSum = new int[nPixels];

        for (int ch = 0; ch < channelUint8.length; ch++) {
            int cr = (channelColors[ch] >> 16) & 0xFF;
            int cg = (channelColors[ch] >> 8) & 0xFF;
            int cb = channelColors[ch] & 0xFF;

            for (int i = 0; i < nPixels; i++) {
                int v = Byte.toUnsignedInt(channelUint8[ch][i]);
                rSum[i] += v * cr / 255;
                gSum[i] += v * cg / 255;
                bSum[i] += v * cb / 255;
            }
        }

        // Clamp and pack
        int[] rgb = new int[nPixels];
        for (int i = 0; i < nPixels; i++) {
            int r = Math.min(rSum[i], 255);
            int g = Math.min(gSum[i], 255);
            int b = Math.min(bSum[i], 255);
            rgb[i] = (r << 16) | (g << 8) | b;
        }
        return rgb;
    }

    // ================================================================
    // Channel resolution
    // ================================================================

    /**
     * Resolve requested channel indices, validating against the series.
     */
    static int[] resolveChannels(int[] requested, SeriesInfo si) {
        if (requested == null) {
            // All channels
            int[] all = new int[si.sizeC()];
            for (int i = 0; i < si.sizeC(); i++) all[i] = i;
            return all;
        }
        for (int ch : requested) {
            if (ch >= si.sizeC()) {
                throw new IllegalArgumentException(
                        "channel " + ch + " out of range, series has "
                        + si.sizeC() + " channel(s)");
            }
        }
        return requested;
    }

    // ================================================================
    // PNG encoding
    // ================================================================

    /**
     * Encode packed RGB pixel data as a PNG, with optional downsampling.
     */
    static byte[] encodeRgbPng(int[] rgb, int srcWidth, int srcHeight,
                                int outWidth, int outHeight)
            throws IOException {
        BufferedImage image;

        if (srcWidth == outWidth && srcHeight == outHeight) {
            image = new BufferedImage(outWidth, outHeight,
                    BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, outWidth, outHeight, rgb, 0, outWidth);
        } else {
            image = downsampleRgb(rgb, srcWidth, srcHeight,
                                   outWidth, outHeight);
        }

        var baos = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", baos)) {
            throw new IOException("PNG writer not available");
        }
        return baos.toByteArray();
    }

    /**
     * Downsample packed RGB data using area averaging.
     */
    static BufferedImage downsampleRgb(int[] rgb,
                                        int srcWidth, int srcHeight,
                                        int dstWidth, int dstHeight) {
        var image = new BufferedImage(dstWidth, dstHeight,
                BufferedImage.TYPE_INT_RGB);

        double xScale = (double) srcWidth / dstWidth;
        double yScale = (double) srcHeight / dstHeight;

        for (int dy = 0; dy < dstHeight; dy++) {
            double srcYStart = dy * yScale;
            double srcYEnd = (dy + 1) * yScale;
            int srcY0 = (int) srcYStart;
            int srcY1 = Math.min((int) Math.ceil(srcYEnd), srcHeight);

            for (int dx = 0; dx < dstWidth; dx++) {
                double srcXStart = dx * xScale;
                double srcXEnd = (dx + 1) * xScale;
                int srcX0 = (int) srcXStart;
                int srcX1 = Math.min((int) Math.ceil(srcXEnd), srcWidth);

                long rSum = 0, gSum = 0, bSum = 0;
                int count = 0;
                for (int sy = srcY0; sy < srcY1; sy++) {
                    for (int sx = srcX0; sx < srcX1; sx++) {
                        int pixel = rgb[sy * srcWidth + sx];
                        rSum += (pixel >> 16) & 0xFF;
                        gSum += (pixel >> 8) & 0xFF;
                        bSum += pixel & 0xFF;
                        count++;
                    }
                }
                if (count > 0) {
                    int r = (int) Math.round((double) rSum / count);
                    int g = (int) Math.round((double) gSum / count);
                    int b = (int) Math.round((double) bSum / count);
                    image.setRGB(dx, dy, (r << 16) | (g << 8) | b);
                }
            }
        }
        return image;
    }

    // ---- CancellableTask.Result → ToolResult conversion ----

    private static <T> ToolResult<T> unwrap(Result<T> result) {
        return switch (result) {
            case Result.Completed<T> c -> ToolResult.success(c.value());
            case Result.Failed<T> f -> convertError(f.error());
            case Result.TimedOut<T> t -> ToolResult.timeout(
                    "Operation timed out after " + t.elapsed().toMillis()
                    + " ms (interrupted " + t.interruptsSent() + " time(s),"
                    + " thread "
                    + (t.threadStillAlive() ? "still alive" : "terminated")
                    + ")");
        };
    }

    private static <T> ToolResult<T> convertError(Throwable error) {
        if (error instanceof InterruptedException) {
            return ToolResult.timeout(
                    "Operation was interrupted (likely timed out)");
        }
        if (error instanceof IllegalArgumentException) {
            return ToolResult.invalidArgument(error.getMessage());
        }
        if (error instanceof IOException) {
            return ToolResult.ioError(error.getMessage(), error);
        }
        return ToolResult.ioError(
                error.getClass().getSimpleName() + ": " + error.getMessage(),
                error);
    }
}
