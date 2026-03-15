package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SeriesInfoTest {

    private SeriesInfo make(int x, int y, int z, int c, int t, PixelType pt) {
        return new SeriesInfo("test", x, y, z, c, t, pt, "XYCZT",
                null, null, null, null, null, null, null);
    }

    @Test
    void computedSizesForTypicalConfocalStack() {
        // 512x512, 30 Z-slices, 3 channels, 1 timepoint, 16-bit
        var s = make(512, 512, 30, 3, 1, PixelType.UINT16);
        assertEquals(90, s.planeCount());           // 30 * 3 * 1
        assertEquals(512L * 512 * 90, s.totalPixels());
        assertEquals(512L * 512 * 90 * 2, s.rawDataBytes());  // * 2 bytes
    }

    @Test
    void rawDataBytesDoesNotOverflowForLargeImages() {
        // 10000 x 10000 x 100 x 4 x 1, float32 = 160 GB
        var s = make(10_000, 10_000, 100, 4, 1, PixelType.FLOAT);
        long expected = 10_000L * 10_000 * 100 * 4 * 4;  // 160_000_000_000
        assertEquals(expected, s.rawDataBytes());
        assertTrue(s.rawDataBytes() > Integer.MAX_VALUE,
                "should exceed int range without overflow");
    }

    @Test
    void rejectsZeroDimensions() {
        assertThrows(IllegalArgumentException.class, () -> make(0, 512, 1, 1, 1, PixelType.UINT8));
        assertThrows(IllegalArgumentException.class, () -> make(512, 0, 1, 1, 1, PixelType.UINT8));
        assertThrows(IllegalArgumentException.class, () -> make(512, 512, 0, 1, 1, PixelType.UINT8));
        assertThrows(IllegalArgumentException.class, () -> make(512, 512, 1, 0, 1, PixelType.UINT8));
        assertThrows(IllegalArgumentException.class, () -> make(512, 512, 1, 1, 0, PixelType.UINT8));
    }

    @Test
    void nullChannelListBecomesEmpty() {
        var s = make(64, 64, 1, 1, 1, PixelType.UINT8);
        assertNotNull(s.channels());
        assertTrue(s.channels().isEmpty());
    }

    @Test
    void channelListIsDefensivelyCopied() {
        var channels = new java.util.ArrayList<>(List.of(ChannelInfo.named(0, "GFP")));
        var s = new SeriesInfo("test", 64, 64, 1, 1, 1, PixelType.UINT8,
                "XYCZT", null, null, null, channels, null, null, null);
        channels.add(ChannelInfo.named(1, "DAPI"));
        assertEquals(1, s.channels().size(), "modification of original list must not affect record");
    }

    @Test
    void extraMetadataIsDefensivelyCopied() {
        var meta = new java.util.HashMap<>(Map.of("key", "value"));
        var s = new SeriesInfo("test", 64, 64, 1, 1, 1, PixelType.UINT8,
                "XYCZT", null, null, null, null, null, null, meta);
        meta.put("sneaky", "mutation");
        assertFalse(s.extraMetadata().containsKey("sneaky"));
    }
}
