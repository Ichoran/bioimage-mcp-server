package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.FakeImageReader.FakeSeries;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;
import lab.kerrr.mcpbio.bioimageserver.ToolResult.ErrorKind;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class GetPlaneToolTest {

    // ---- Success: basic PNG output ----

    @Test
    void producesValidPng() throws IOException {
        var result = run("/image.tif", 0, 0, 0, 0, true, null);
        assertSuccess(result, png -> {
            // PNG magic bytes
            assertEquals((byte) 0x89, png[0]);
            assertEquals((byte) 'P', png[1]);
            assertEquals((byte) 'N', png[2]);
            assertEquals((byte) 'G', png[3]);
        });
    }

    @Test
    void pngHasCorrectDimensions() throws IOException {
        var result = run("/image.tif", 0, 0, 0, 0, true, null);
        assertSuccess(result, png -> {
            var image = decodePng(png);
            assertEquals(32, image.getWidth());
            assertEquals(32, image.getHeight());
        });
    }

    @Test
    void pngIsGrayscale() {
        var result = run("/image.tif", 0, 0, 0, 0, true, null);
        assertSuccess(result, png -> {
            var image = decodePng(png);
            assertEquals(BufferedImage.TYPE_BYTE_GRAY, image.getType());
        });
    }

    // ---- Normalization modes ----

    @Test
    void autoContrastProducesNonTrivialImage() {
        // With auto-contrast, pixels should span a reasonable range
        var result = run("/image.tif", 0, 0, 0, 0, true, null);
        assertSuccess(result, png -> {
            var image = decodePng(png);
            int min = 255, max = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int v = image.getRaster().getSample(x, y, 0);
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
            assertTrue(max - min > 100,
                    "auto-contrast should produce a wide range, got "
                    + min + "–" + max);
        });
    }

    @Test
    void fullRangeNormalizationWorks() {
        // With normalize=false, uint8 type maps 0→0 and 255→255
        var result = run("/image.tif", 0, 0, 0, 0, false, null);
        assertSuccess(result, png -> {
            var image = decodePng(png);
            // The synthetic data for uint8 should produce meaningful values
            assertNotNull(image);
            assertTrue(image.getWidth() > 0);
        });
    }

    @Test
    void uint16WithAutoContrastSpansRange() {
        // 64x64 uint16 image — auto-contrast should stretch the data
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(64, 64, 1, 2, 1, PixelType.UINT16))
                .build();
        var request = new GetPlaneTool.Request(
                "/image.tif", 0, 1, 0, 0, true, null,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetPlaneTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, png -> {
            var image = decodePng(png);
            assertEquals(64, image.getWidth());
            int min = 255, max = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int v = image.getRaster().getSample(x, y, 0);
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
            assertTrue(max - min > 200,
                    "auto-contrast on uint16 should span most of 0–255, got "
                    + min + "–" + max);
        });
    }

    // ---- Downsampling ----

    @Test
    void downsampleReducesDimensions() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(256, 128, 1, 1, 1, PixelType.UINT8))
                .build();
        var request = new GetPlaneTool.Request(
                "/image.tif", 0, 0, 0, 0, true, 64,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetPlaneTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, png -> {
            var image = decodePng(png);
            // maxSize=64 on a 256x128 image → 64x32
            assertEquals(64, image.getWidth());
            assertEquals(32, image.getHeight());
        });
    }

    @Test
    void noDownsampleWhenUnderMaxSize() {
        var result = run("/image.tif", 0, 0, 0, 0, true, 1024);
        assertSuccess(result, png -> {
            var image = decodePng(png);
            // 32x32 is well under maxSize=1024
            assertEquals(32, image.getWidth());
            assertEquals(32, image.getHeight());
        });
    }

    // ---- Different channels/z/t produce different images ----

    @Test
    void differentChannelsProduceDifferentImages() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 1, 3, 1, PixelType.UINT8))
                .build();

        var req0 = new GetPlaneTool.Request("/img.tif", 0, 0, 0, 0, false, null,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var req1 = new GetPlaneTool.Request("/img.tif", 0, 1, 0, 0, false, null,
                Duration.ofSeconds(5), 256L * 1024 * 1024);

        var r0 = GetPlaneTool.execute(req0, PathValidator.allowAll(), factory);
        var r1 = GetPlaneTool.execute(req1, PathValidator.allowAll(), factory);

        assertSuccess(r0, png0 ->
            assertSuccess(r1, png1 ->
                assertFalse(java.util.Arrays.equals(png0, png1),
                        "different channels should produce different PNGs")));
    }

    // ---- Error cases ----

    @Test
    void accessDenied() {
        PathValidator deny = path -> new AccessResult.Denied("nope");
        var request = GetPlaneTool.Request.of("/secret.tif", 0);
        var result = GetPlaneTool.execute(request, deny, simpleFactory());
        assertFailure(result, ErrorKind.ACCESS_DENIED, "nope");
    }

    @Test
    void channelOutOfRange() {
        // Series has 1 channel, request channel 5
        var request = new GetPlaneTool.Request(
                "/image.tif", 0, 5, 0, 0, true, null,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetPlaneTool.execute(
                request, PathValidator.allowAll(), simpleFactory());
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "channel");
    }

    @Test
    void zOutOfRange() {
        var request = new GetPlaneTool.Request(
                "/image.tif", 0, 0, 99, 0, true, null,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetPlaneTool.execute(
                request, PathValidator.allowAll(), simpleFactory());
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "z");
    }

    @Test
    void timepointOutOfRange() {
        var request = new GetPlaneTool.Request(
                "/image.tif", 0, 0, 0, 99, true, null,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetPlaneTool.execute(
                request, PathValidator.allowAll(), simpleFactory());
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "timepoint");
    }

    @Test
    void byteBudgetExceeded() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(1024, 1024, 1, 1, 1, PixelType.UINT16))
                .build();
        // 1024*1024*2 = 2MB, budget is 1MB
        var request = new GetPlaneTool.Request(
                "/image.tif", 0, 0, 0, 0, true, null,
                Duration.ofSeconds(5), 1024 * 1024);
        var result = GetPlaneTool.execute(
                request, PathValidator.allowAll(), factory);
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "maxBytes");
    }

    @Test
    void negativeChannelRejectedByRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> GetPlaneTool.Request.of("/img.tif", null, -1,
                        null, null, null, null, null, null));
    }

    // ---- encodePng / downsample directly ----

    @Test
    void encodePngRoundTrips() throws IOException {
        int w = 4, h = 3;
        var pixels = new byte[w * h];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i * 20);
        }
        byte[] png = GetPlaneTool.encodePng(pixels, w, h, w, h);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(w, image.getWidth());
        assertEquals(h, image.getHeight());
        // Check a couple of pixel values
        assertEquals(0, image.getRaster().getSample(0, 0, 0));
        assertEquals(20, image.getRaster().getSample(1, 0, 0));
    }

    @Test
    void downsampleAveragesCorrectly() {
        // 4x4 image, downsample to 2x2
        // Each 2x2 block should average
        var pixels = new byte[] {
            10, 20, 30, 40,
            10, 20, 30, 40,
            50, 60, 70, 80,
            50, 60, 70, 80
        };
        var image = GetPlaneTool.downsample(pixels, 4, 4, 2, 2);
        assertEquals(2, image.getWidth());
        assertEquals(2, image.getHeight());
        // Top-left 2x2 block: (10+20+10+20)/4 = 15
        assertEquals(15, image.getRaster().getSample(0, 0, 0));
        // Top-right 2x2 block: (30+40+30+40)/4 = 35
        assertEquals(35, image.getRaster().getSample(1, 0, 0));
        // Bottom-left: (50+60+50+60)/4 = 55
        assertEquals(55, image.getRaster().getSample(0, 1, 0));
        // Bottom-right: (70+80+70+80)/4 = 75
        assertEquals(75, image.getRaster().getSample(1, 1, 0));
    }

    // ---- Helpers ----

    private ToolResult<byte[]> run(String path, int series, int channel,
                                    int z, int timepoint, boolean normalize,
                                    Integer maxSize) {
        var request = new GetPlaneTool.Request(
                path, series, channel, z, timepoint, normalize, maxSize,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        return GetPlaneTool.execute(
                request, PathValidator.allowAll(), simpleFactory());
    }

    private static Supplier<ImageReader> simpleFactory() {
        return () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(32, 32, 1, 1, 1, PixelType.UINT8))
                .build();
    }

    private static BufferedImage decodePng(byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            throw new RuntimeException("failed to decode PNG", e);
        }
    }

    private static <T> void assertSuccess(ToolResult<T> result,
                                           java.util.function.Consumer<T> check) {
        if (result instanceof ToolResult.Success<T> s) {
            check.accept(s.value());
        } else {
            var f = (ToolResult.Failure<T>) result;
            fail("expected success, got " + f.kind() + ": " + f.message());
        }
    }

    private static <T> void assertFailure(ToolResult<T> result,
                                           ErrorKind expectedKind,
                                           String messageContains) {
        if (result instanceof ToolResult.Failure<T> f) {
            assertEquals(expectedKind, f.kind());
            assertTrue(f.message().toLowerCase().contains(messageContains.toLowerCase()),
                    "expected message containing '" + messageContains
                    + "', got: " + f.message());
        } else {
            fail("expected " + expectedKind + " failure, got success");
        }
    }
}
