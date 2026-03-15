package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.CancellableTask.Result;
import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * Extracts a single 2D plane from an image file as a grayscale PNG.
 *
 * <p>This is the first tool that touches pixel data.  It reads one
 * (channel, z, timepoint) plane, optionally applies auto-contrast
 * (percentile stretch) or full-range normalization, and encodes the
 * result as an 8-bit grayscale PNG.
 */
public final class GetPlaneTool {

    private GetPlaneTool() {}

    /** Default percentile for the auto-contrast black point. */
    static final double LOW_PERCENTILE = 0.1;
    /** Default percentile for the auto-contrast white point. */
    static final double HIGH_PERCENTILE = 99.9;

    /**
     * Parameters for a {@code get_plane} call.
     *
     * @param path             path to the image file
     * @param series           zero-based series index
     * @param channel          zero-based channel index
     * @param z                zero-based Z-slice index
     * @param timepoint        zero-based timepoint index
     * @param normalize        if true, auto-contrast via percentile stretch;
     *                         if false, map the full type range to 0–255
     * @param maxSize          if non-null, downsample so the largest
     *                         dimension does not exceed this value
     * @param timeout          wall-clock time limit
     * @param maxBytes         approximate cap on raw pixel bytes to read
     */
    public record Request(
            String path,
            int series,
            int channel,
            int z,
            int timepoint,
            boolean normalize,
            Integer maxSize,
            Duration timeout,
            long maxBytes) {

        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
        public static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

        public Request {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("path must not be blank");
            }
            if (series < 0) {
                throw new IllegalArgumentException("series must be non-negative");
            }
            if (channel < 0) {
                throw new IllegalArgumentException("channel must be non-negative");
            }
            if (z < 0) {
                throw new IllegalArgumentException("z must be non-negative");
            }
            if (timepoint < 0) {
                throw new IllegalArgumentException("timepoint must be non-negative");
            }
            if (maxSize != null && maxSize < 1) {
                throw new IllegalArgumentException("maxSize must be positive");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive");
            }
        }

        public static Request of(String path, Integer series, Integer channel,
                                  Integer z, Integer timepoint,
                                  Boolean normalize, Integer maxSize,
                                  Duration timeout, Long maxBytes) {
            return new Request(
                    path,
                    series != null ? series : 0,
                    channel != null ? channel : 0,
                    z != null ? z : 0,
                    timepoint != null ? timepoint : 0,
                    normalize != null ? normalize : true,
                    maxSize,
                    timeout != null ? timeout : DEFAULT_TIMEOUT,
                    maxBytes != null ? maxBytes : DEFAULT_MAX_BYTES);
        }

        public static Request of(String path, int channel) {
            return of(path, null, channel, null, null, null, null, null, null);
        }
    }

    /**
     * Execute the get_plane tool.
     *
     * @param request       the extraction parameters
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

                // Validate coordinates
                var meta = reader.getMetadata(request.series(), DetailLevel.SUMMARY);
                var si = meta.detailedSeries();
                validateCoordinates(request, si);

                // Check byte budget
                long planeBytes = (long) si.sizeX() * si.sizeY()
                                * si.pixelType().bytesPerPixel();
                if (planeBytes > request.maxBytes()) {
                    throw new IllegalArgumentException(
                            "plane size (" + planeBytes + " bytes) exceeds"
                            + " maxBytes budget (" + request.maxBytes() + ")");
                }

                // Read the plane
                byte[] raw = reader.readPlane(
                        request.series(), request.channel(),
                        request.z(), request.timepoint());

                // Convert to uint8
                ByteOrder order = reader.isLittleEndian(request.series())
                        ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
                double[] values = PixelConverter.toDoubles(
                        raw, si.pixelType(), order);
                byte[] uint8;
                if (request.normalize()) {
                    uint8 = PixelConverter.toUint8AutoContrast(
                            values, LOW_PERCENTILE, HIGH_PERCENTILE);
                } else {
                    uint8 = PixelConverter.toUint8FullRange(
                            values, si.pixelType());
                }

                // Downsample if needed
                int outWidth = si.sizeX();
                int outHeight = si.sizeY();
                if (request.maxSize() != null) {
                    int maxDim = Math.max(outWidth, outHeight);
                    if (maxDim > request.maxSize()) {
                        double scale = (double) request.maxSize() / maxDim;
                        outWidth = Math.max(1, (int) Math.round(outWidth * scale));
                        outHeight = Math.max(1, (int) Math.round(outHeight * scale));
                    }
                }

                // Encode as grayscale PNG
                return encodePng(uint8, si.sizeX(), si.sizeY(),
                                 outWidth, outHeight);
            }
        });

        return unwrap(result);
    }

    /**
     * Encode uint8 pixel data as a grayscale PNG.
     *
     * <p>If outWidth/outHeight differ from srcWidth/srcHeight, the image
     * is downsampled using area averaging.
     */
    static byte[] encodePng(byte[] uint8, int srcWidth, int srcHeight,
                            int outWidth, int outHeight) throws IOException {
        BufferedImage image;

        if (srcWidth == outWidth && srcHeight == outHeight) {
            // No downsampling — direct copy
            image = new BufferedImage(outWidth, outHeight,
                    BufferedImage.TYPE_BYTE_GRAY);
            var raster = image.getRaster();
            for (int y = 0; y < outHeight; y++) {
                for (int x = 0; x < outWidth; x++) {
                    raster.setSample(x, y, 0,
                            Byte.toUnsignedInt(uint8[y * srcWidth + x]));
                }
            }
        } else {
            // Area-average downsampling
            image = downsample(uint8, srcWidth, srcHeight, outWidth, outHeight);
        }

        var baos = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", baos)) {
            throw new IOException("PNG writer not available");
        }
        return baos.toByteArray();
    }

    /**
     * Downsample uint8 grayscale data using area averaging.
     */
    static BufferedImage downsample(byte[] uint8,
                                    int srcWidth, int srcHeight,
                                    int dstWidth, int dstHeight) {
        var image = new BufferedImage(dstWidth, dstHeight,
                BufferedImage.TYPE_BYTE_GRAY);
        var raster = image.getRaster();

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

                // Average over the source pixels that contribute
                double sum = 0;
                int count = 0;
                for (int sy = srcY0; sy < srcY1; sy++) {
                    for (int sx = srcX0; sx < srcX1; sx++) {
                        sum += Byte.toUnsignedInt(uint8[sy * srcWidth + sx]);
                        count++;
                    }
                }
                raster.setSample(dx, dy, 0,
                        count > 0 ? (int) Math.round(sum / count) : 0);
            }
        }
        return image;
    }

    private static void validateCoordinates(Request request, SeriesInfo si) {
        if (request.series() >= 0) {
            // Series was already validated by getMetadata
        }
        if (request.channel() >= si.sizeC()) {
            throw new IllegalArgumentException(
                    "channel " + request.channel()
                    + " out of range, series has " + si.sizeC() + " channel(s)");
        }
        if (request.z() >= si.sizeZ()) {
            throw new IllegalArgumentException(
                    "z " + request.z()
                    + " out of range, series has " + si.sizeZ() + " Z-slice(s)");
        }
        if (request.timepoint() >= si.sizeT()) {
            throw new IllegalArgumentException(
                    "timepoint " + request.timepoint()
                    + " out of range, series has " + si.sizeT() + " timepoint(s)");
        }
    }

    // ---- CancellableTask.Result → ToolResult conversion ----
    // Same pattern as InspectImageTool.

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
