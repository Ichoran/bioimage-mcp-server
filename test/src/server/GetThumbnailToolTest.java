package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.FakeImageReader.FakeSeries;
import lab.kerrr.mcpbio.bioimageserver.GetThumbnailTool.Projection;
import lab.kerrr.mcpbio.bioimageserver.GetThumbnailTool.ThumbnailResult;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;
import lab.kerrr.mcpbio.bioimageserver.ToolResult.ErrorKind;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class GetThumbnailToolTest {

    // ================================================================
    // Success: basic PNG output
    // ================================================================

    @Test
    void producesValidPng() {
        var result = runSimple("/img.tif");
        assertSuccessPng(result, png -> {
            assertEquals((byte) 0x89, png[0]);
            assertEquals((byte) 'P', png[1]);
            assertEquals((byte) 'N', png[2]);
            assertEquals((byte) 'G', png[3]);
        });
    }

    @Test
    void pngIsRgb() {
        var result = runSimple("/img.tif");
        assertSuccessPng(result, png -> {
            var image = decodePng(png);
            // PNG decoder may choose different internal representations
            // (TYPE_INT_RGB=1, TYPE_3BYTE_BGR=5, etc.) but it should
            // have 3 color bands
            assertEquals(3, image.getRaster().getNumBands(),
                    "thumbnail should be RGB (3 bands)");
        });
    }

    @Test
    void singleChannelDefaultsToGreen() {
        // 1-channel image: should be green-tinted
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 1, 1, 1, PixelType.UINT8))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MID_SLICE, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccessPng(result, png -> {
            var image = decodePng(png);
            // Pick a non-zero pixel and check it's greenish (R=0, G>0, B=0)
            boolean foundGreen = false;
            for (int y = 0; y < image.getHeight() && !foundGreen; y++) {
                for (int x = 0; x < image.getWidth() && !foundGreen; x++) {
                    int rgb = image.getRGB(x, y) & 0x00FFFFFF;
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    if (g > 0) {
                        assertEquals(0, r, "single channel should have no red");
                        assertEquals(0, b, "single channel should have no blue");
                        foundGreen = true;
                    }
                }
            }
            assertTrue(foundGreen, "should have at least one non-zero green pixel");
        });
    }

    // ================================================================
    // Projection modes
    // ================================================================

    @Test
    void midSliceReadsOnlyMiddlePlane() {
        // 4 Z-slices: mid_slice should read Z=2
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(8, 8, 4, 1, 1, PixelType.UINT8))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MID_SLICE, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);
        assertSuccessPng(result, png -> assertNotNull(decodePng(png)));
    }

    @Test
    void maxIntensityProjectionIsAtLeastAsBrightAsMidSlice() {
        // Max projection should produce values >= mid-slice at every pixel
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 5, 1, 1, PixelType.UINT8))
                .build();

        var midReq = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MID_SLICE, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var maxReq = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MAX_INTENSITY, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);

        var midResult = GetThumbnailTool.execute(
                midReq, PathValidator.allowAll(), factory);
        var maxResult = GetThumbnailTool.execute(
                maxReq, PathValidator.allowAll(), factory);

        assertSuccessPng(midResult, midPng -> {
            assertSuccessPng(maxResult, maxPng -> {
                var midImg = decodePng(midPng);
                var maxImg = decodePng(maxPng);
                // Sum all green channel values (single channel → green)
                long midSum = sumGreen(midImg);
                long maxSum = sumGreen(maxImg);
                assertTrue(maxSum >= midSum,
                        "max projection (" + maxSum
                        + ") should be >= mid-slice (" + midSum + ")");
            });
        });
    }

    @Test
    void sumProjectionDiffersFromMaxProjection() {
        // Sum and max should generally produce different results
        // (unless all Z slices are identical, which they aren't with
        // the fake reader's z-dependent formula)
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 5, 1, 1, PixelType.UINT8))
                .build();

        var sumReq = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.SUM, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var maxReq = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MAX_INTENSITY, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);

        var sumResult = GetThumbnailTool.execute(
                sumReq, PathValidator.allowAll(), factory);
        var maxResult = GetThumbnailTool.execute(
                maxReq, PathValidator.allowAll(), factory);

        assertSuccessPng(sumResult, sumPng -> {
            assertSuccessPng(maxResult, maxPng -> {
                assertFalse(java.util.Arrays.equals(sumPng, maxPng),
                        "sum and max projections should differ");
            });
        });
    }

    // ================================================================
    // Adaptive projection
    // ================================================================

    @Test
    void adaptiveUpgradesToMaxWhenBudgetAllows() {
        // Small image, generous budget → should upgrade to MAX_INTENSITY
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 5, 2, 1, PixelType.UINT8))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.ADAPTIVE, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, tr -> {
            assertEquals(Projection.MAX_INTENSITY, tr.projectionUsed(),
                    "adaptive should upgrade to max with generous budget");
            assertNotNull(decodePng(tr.png()));
        });
    }

    @Test
    void adaptiveFallsToMidSliceWhenByteBudgetTight() {
        // 3 channels × 10 Z-slices × 32×32 = 30720 bytes for max,
        // but budget is only 3200 (enough for mid-slice: 3 × 1 × 1024)
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(32, 32, 10, 3, 1, PixelType.UINT8))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.ADAPTIVE, null, 0, 1024,
                Duration.ofSeconds(5), 3200);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, tr -> {
            assertEquals(Projection.MID_SLICE, tr.projectionUsed(),
                    "adaptive should fall back to mid-slice with tight budget");
            assertNotNull(decodePng(tr.png()));
        });
    }

    @Test
    void adaptiveWithSingleZSliceUsesMidSlice() {
        // sizeZ=1: no upgrade possible, should use MID_SLICE
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 1, 2, 1, PixelType.UINT8))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.ADAPTIVE, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, tr ->
            assertEquals(Projection.MID_SLICE, tr.projectionUsed(),
                    "sizeZ=1 should resolve to mid-slice"));
    }

    @Test
    void adaptiveMaxMatchesExplicitMax() {
        // When adaptive upgrades to max, the result should match
        // an explicit MAX_INTENSITY request
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 5, 1, 1, PixelType.UINT8))
                .build();

        var adaptiveReq = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.ADAPTIVE, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var explicitReq = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MAX_INTENSITY, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);

        var adaptiveResult = GetThumbnailTool.execute(
                adaptiveReq, PathValidator.allowAll(), factory);
        var explicitResult = GetThumbnailTool.execute(
                explicitReq, PathValidator.allowAll(), factory);

        assertSuccess(adaptiveResult, adaptiveTr -> {
            assertEquals(Projection.MAX_INTENSITY,
                    adaptiveTr.projectionUsed());
            assertSuccess(explicitResult, explicitTr ->
                assertArrayEquals(explicitTr.png(), adaptiveTr.png(),
                        "adaptive max should match explicit max"));
        });
    }

    @Test
    void adaptiveIsDefaultProjection() {
        var request = GetThumbnailTool.Request.of("/img.tif");
        assertEquals(Projection.ADAPTIVE, request.projection());
    }

    // ================================================================
    // Multi-channel compositing
    // ================================================================

    @Test
    void twoChannelsProducesCyanAndMagenta() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 1, 2, 1, PixelType.UINT8))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MID_SLICE, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccessPng(result, png -> {
            var image = decodePng(png);
            // With two channels composited, we should see non-zero values
            // in R, G, and B (cyan has G+B, magenta has R+B)
            boolean hasR = false, hasG = false, hasB = false;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y) & 0x00FFFFFF;
                    if (((rgb >> 16) & 0xFF) > 0) hasR = true;
                    if (((rgb >> 8) & 0xFF) > 0) hasG = true;
                    if ((rgb & 0xFF) > 0) hasB = true;
                }
            }
            assertTrue(hasR, "two-channel composite should have red component");
            assertTrue(hasG, "two-channel composite should have green component");
            assertTrue(hasB, "two-channel composite should have blue component");
        });
    }

    @Test
    void channelSubsetSelectsRequestedChannels() {
        // 3-channel image, request only channel 1
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 1, 3, 1, PixelType.UINT8))
                .build();
        var allReq = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MID_SLICE, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var subReq = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MID_SLICE, new int[] { 1 }, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);

        var allResult = GetThumbnailTool.execute(
                allReq, PathValidator.allowAll(), factory);
        var subResult = GetThumbnailTool.execute(
                subReq, PathValidator.allowAll(), factory);

        assertSuccessPng(allResult, allPng ->
            assertSuccessPng(subResult, subPng ->
                assertFalse(java.util.Arrays.equals(allPng, subPng),
                        "channel subset should differ from all channels")));
    }

    @Test
    void channelColorFromMetadataIsUsed() {
        // Create a single-channel series with a red channel color
        var redChannel = new ChannelInfo(0, "Red", null,
                OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalInt.of(0xFFFF0000)); // ARGB red
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(new FakeSeries(null, 16, 16, 1, 1, 1,
                        PixelType.UINT8, "XYCZT", null, null, null,
                        List.of(redChannel), null, null, java.util.Map.of()))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MID_SLICE, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccessPng(result, png -> {
            var image = decodePng(png);
            // Should be red-tinted (G=0, B=0 for non-zero pixels)
            boolean foundRed = false;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y) & 0x00FFFFFF;
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    if (r > 0) {
                        assertEquals(0, g, "red channel should have no green");
                        assertEquals(0, b, "red channel should have no blue");
                        foundRed = true;
                    }
                }
            }
            assertTrue(foundRed, "should have red pixels from metadata color");
        });
    }

    // ================================================================
    // Downsampling
    // ================================================================

    @Test
    void downsampleReducesDimensions() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(256, 128, 1, 1, 1, PixelType.UINT8))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MID_SLICE, null, 0, 64,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccessPng(result, png -> {
            var image = decodePng(png);
            assertEquals(64, image.getWidth());
            assertEquals(32, image.getHeight());
        });
    }

    @Test
    void noDownsampleWhenUnderMaxSize() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(32, 32, 1, 1, 1, PixelType.UINT8))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MID_SLICE, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccessPng(result, png -> {
            var image = decodePng(png);
            assertEquals(32, image.getWidth());
            assertEquals(32, image.getHeight());
        });
    }

    // ================================================================
    // Uint16 support
    // ================================================================

    @Test
    void uint16ImageProducesValidThumbnail() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(32, 32, 3, 2, 1, PixelType.UINT16))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MAX_INTENSITY, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccessPng(result, png -> {
            var image = decodePng(png);
            assertEquals(32, image.getWidth());
            assertEquals(32, image.getHeight());
        });
    }

    // ================================================================
    // Compositing unit tests
    // ================================================================

    @Test
    void compositeSingleGreenChannel() {
        byte[][] channels = { new byte[] { (byte) 128, (byte) 255 } };
        int[] colors = { 0x00FF00 }; // green
        int[] rgb = GetThumbnailTool.composite(channels, colors, 2);
        // pixel 0: v=128, green only → (0, 128*255/255, 0) = (0, 128, 0)
        assertEquals(0, (rgb[0] >> 16) & 0xFF); // R
        assertEquals(128, (rgb[0] >> 8) & 0xFF); // G
        assertEquals(0, rgb[0] & 0xFF); // B
        // pixel 1: v=255 → (0, 255, 0)
        assertEquals(0, (rgb[1] >> 16) & 0xFF);
        assertEquals(255, (rgb[1] >> 8) & 0xFF);
        assertEquals(0, rgb[1] & 0xFF);
    }

    @Test
    void compositeTwoChannelsAdds() {
        byte[][] channels = {
            new byte[] { (byte) 100 },  // channel 0
            new byte[] { (byte) 100 }   // channel 1
        };
        int[] colors = { 0x00FFFF, 0xFF00FF }; // cyan, magenta
        int[] rgb = GetThumbnailTool.composite(channels, colors, 1);
        // cyan:    v=100 → R=0,   G=100, B=100
        // magenta: v=100 → R=100, G=0,   B=100
        // sum:            → R=100, G=100, B=200
        assertEquals(100, (rgb[0] >> 16) & 0xFF); // R
        assertEquals(100, (rgb[0] >> 8) & 0xFF);  // G
        assertEquals(200, rgb[0] & 0xFF);          // B
    }

    @Test
    void compositeClampsSaturatedValues() {
        byte[][] channels = {
            new byte[] { (byte) 200 },
            new byte[] { (byte) 200 }
        };
        int[] colors = { 0x00FF00, 0x00FF00 }; // both green
        int[] rgb = GetThumbnailTool.composite(channels, colors, 1);
        // 200 + 200 = 400 → clamped to 255
        assertEquals(255, (rgb[0] >> 8) & 0xFF);
    }

    // ================================================================
    // Default color logic
    // ================================================================

    @Test
    void defaultColorSingleChannelIsGreen() {
        assertEquals(0x00FF00, GetThumbnailTool.defaultColor(0, 1));
    }

    @Test
    void defaultColorTwoChannelsIsCyanMagenta() {
        assertEquals(0x00FFFF, GetThumbnailTool.defaultColor(0, 2));
        assertEquals(0xFF00FF, GetThumbnailTool.defaultColor(1, 2));
    }

    @Test
    void defaultColorThreeChannelsIsCyanMagentaYellow() {
        assertEquals(0x00FFFF, GetThumbnailTool.defaultColor(0, 3));
        assertEquals(0xFF00FF, GetThumbnailTool.defaultColor(1, 3));
        assertEquals(0xFFFF00, GetThumbnailTool.defaultColor(2, 3));
    }

    // ================================================================
    // Z-slice resolution
    // ================================================================

    @Test
    void midSliceResolvesToMiddleZ() {
        var si = new SeriesInfo(null, 10, 10, 7, 1, 1,
                PixelType.UINT8, "XYCZT", null, null, null,
                List.of(), null, null, null);
        int[] zSlices = GetThumbnailTool.resolveZSlices(
                Projection.MID_SLICE, si);
        assertArrayEquals(new int[] { 3 }, zSlices); // 7/2 = 3
    }

    @Test
    void maxIntensityResolvesToAllZ() {
        var si = new SeriesInfo(null, 10, 10, 4, 1, 1,
                PixelType.UINT8, "XYCZT", null, null, null,
                List.of(), null, null, null);
        int[] zSlices = GetThumbnailTool.resolveZSlices(
                Projection.MAX_INTENSITY, si);
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, zSlices);
    }

    // ================================================================
    // Error cases
    // ================================================================

    @Test
    void accessDenied() {
        PathValidator deny = path -> new AccessResult.Denied("nope");
        var request = GetThumbnailTool.Request.of("/secret.tif");
        var result = GetThumbnailTool.execute(
                request, deny, simpleFactory());
        assertFailure(result, ErrorKind.ACCESS_DENIED, "nope");
    }

    @Test
    void channelOutOfRange() {
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MID_SLICE, new int[] { 5 },
                0, 1024, Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), simpleFactory());
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "channel");
    }

    @Test
    void timepointOutOfRange() {
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 1, 1, 1, PixelType.UINT8))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MID_SLICE, null, 99, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "timepoint");
    }

    @Test
    void explicitMaxByteBudgetExceeded() {
        // Explicit MAX_INTENSITY with tight budget should still fail hard
        // 3 channels × 5 Z slices × 64×64 = 61440 bytes, budget is 1000
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(64, 64, 5, 3, 1, PixelType.UINT8))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.MAX_INTENSITY, null, 0, 1024,
                Duration.ofSeconds(5), 1000);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "maxBytes");
    }

    @Test
    void adaptiveByteBudgetDegrades() {
        // Same scenario as above but with ADAPTIVE — should degrade, not fail
        // 3 channels × 5 Z slices × 64×64 = 61440 bytes for max,
        // but 3 × 1 × 4096 = 12288 for mid-slice; budget = 15000
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(64, 64, 5, 3, 1, PixelType.UINT8))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.ADAPTIVE, null, 0, 1024,
                Duration.ofSeconds(5), 15000);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);

        assertSuccess(result, tr -> {
            assertEquals(Projection.MID_SLICE, tr.projectionUsed(),
                    "adaptive should degrade to mid-slice, not fail");
            assertNotNull(decodePng(tr.png()));
        });
    }

    @Test
    void adaptiveFailsWhenEvenMidSliceExceedsBudget() {
        // Budget too small for even mid-slice: 3 channels × 64×64 = 12288
        Supplier<ImageReader> factory = () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(64, 64, 5, 3, 1, PixelType.UINT8))
                .build();
        var request = new GetThumbnailTool.Request(
                "/img.tif", 0, Projection.ADAPTIVE, null, 0, 1024,
                Duration.ofSeconds(5), 1000);
        var result = GetThumbnailTool.execute(
                request, PathValidator.allowAll(), factory);
        assertFailure(result, ErrorKind.INVALID_ARGUMENT, "mid-slice");
    }

    @Test
    void emptyChannelsArrayRejectedByRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> new GetThumbnailTool.Request(
                        "/img.tif", 0, Projection.MID_SLICE, new int[0],
                        0, 1024, Duration.ofSeconds(5), 256L * 1024 * 1024));
    }

    @Test
    void negativeChannelRejectedByRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> new GetThumbnailTool.Request(
                        "/img.tif", 0, Projection.MID_SLICE,
                        new int[] { -1 },
                        0, 1024, Duration.ofSeconds(5), 256L * 1024 * 1024));
    }

    // ================================================================
    // ThumbnailResult validation
    // ================================================================

    @Test
    void thumbnailResultRejectsAdaptiveProjection() {
        assertThrows(IllegalArgumentException.class,
                () -> new ThumbnailResult(new byte[] { 1 }, Projection.ADAPTIVE));
    }

    // ================================================================
    // resolveColor
    // ================================================================

    @Test
    void resolveColorUsesMetadataWhenPresent() {
        var ch = new ChannelInfo(0, "GFP", null,
                OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalInt.of(0xFF00FF00)); // ARGB green
        int color = GetThumbnailTool.resolveColor(0, List.of(ch), 1);
        assertEquals(0x00FF00, color);
    }

    @Test
    void resolveColorFallsBackToDefault() {
        var ch = ChannelInfo.named(0, "Ch0");
        int color = GetThumbnailTool.resolveColor(0, List.of(ch), 1);
        assertEquals(0x00FF00, color); // default for single channel
    }

    // ================================================================
    // Helpers
    // ================================================================

    private ToolResult<ThumbnailResult> runSimple(String path) {
        var request = new GetThumbnailTool.Request(
                path, 0, Projection.MID_SLICE, null, 0, 1024,
                Duration.ofSeconds(5), 256L * 1024 * 1024);
        return GetThumbnailTool.execute(
                request, PathValidator.allowAll(), simpleFactory());
    }

    private static Supplier<ImageReader> simpleFactory() {
        return () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(32, 32, 3, 2, 1, PixelType.UINT8))
                .build();
    }

    private static BufferedImage decodePng(byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            throw new RuntimeException("failed to decode PNG", e);
        }
    }

    private static long sumGreen(BufferedImage image) {
        long sum = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                sum += (image.getRGB(x, y) >> 8) & 0xFF;
            }
        }
        return sum;
    }

    /** Assert success and check the ThumbnailResult. */
    private static void assertSuccess(ToolResult<ThumbnailResult> result,
                                       java.util.function.Consumer<ThumbnailResult> check) {
        if (result instanceof ToolResult.Success<ThumbnailResult> s) {
            check.accept(s.value());
        } else {
            var f = (ToolResult.Failure<ThumbnailResult>) result;
            fail("expected success, got " + f.kind() + ": " + f.message());
        }
    }

    /** Assert success and check the PNG bytes directly. */
    private static void assertSuccessPng(ToolResult<ThumbnailResult> result,
                                          java.util.function.Consumer<byte[]> check) {
        assertSuccess(result, tr -> check.accept(tr.png()));
    }

    private static void assertFailure(ToolResult<ThumbnailResult> result,
                                       ErrorKind expectedKind,
                                       String messageContains) {
        if (result instanceof ToolResult.Failure<ThumbnailResult> f) {
            assertEquals(expectedKind, f.kind());
            assertTrue(f.message().toLowerCase().contains(
                    messageContains.toLowerCase()),
                    "expected message containing '" + messageContains
                    + "', got: " + f.message());
        } else {
            fail("expected " + expectedKind + " failure, got success");
        }
    }
}
