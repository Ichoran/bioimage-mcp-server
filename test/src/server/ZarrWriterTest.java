package lab.kerrr.mcpbio.bioimageserver;

import dev.zarr.zarrjava.v3.Array;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Round-trip and structural tests for {@link ZarrWriter}. */
class ZarrWriterTest {

    private static final int X = 3, Y = 4, Z = 2, C = 2, T = 1;

    /** Deterministic uint16 voxel value, distinct per (c,z,y,x). */
    private static int value(int c, int z, int y, int x) {
        return (c * 1000 + z * 100 + y * 10 + x) & 0xFFFF;
    }

    private static String omeXml(boolean bigEndian) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <OME xmlns="http://www.openmicroscopy.org/Schemas/OME/2016-06">
              <Image ID="Image:0" Name="test">
                <Pixels ID="Pixels:0" DimensionOrder="XYCZT" Type="uint16"
                  BigEndian="%s" SizeX="%d" SizeY="%d" SizeZ="%d" SizeC="%d" SizeT="%d"
                  PhysicalSizeX="0.5" PhysicalSizeXUnit="µm"
                  PhysicalSizeY="0.5" PhysicalSizeYUnit="µm"
                  PhysicalSizeZ="2.0" PhysicalSizeZUnit="µm">
                  <Channel ID="Channel:0:0" SamplesPerPixel="1"/>
                  <Channel ID="Channel:0:1" SamplesPerPixel="1"/>
                </Pixels>
              </Image>
            </OME>
            """.formatted(bigEndian, X, Y, Z, C, T);
    }

    /** One (c,z) plane of uint16 in the given byte order, row-major. */
    private static byte[] plane(int c, int z, ByteOrder order) {
        ByteBuffer buf = ByteBuffer.allocate(Y * X * 2).order(order);
        for (int y = 0; y < Y; y++) {
            for (int x = 0; x < X; x++) {
                buf.putShort((short) value(c, z, y, x));
            }
        }
        return buf.array();
    }

    private void writeAll(ZarrWriter w, ByteOrder order) throws IOException {
        w.setSeries(0);
        int planeIdx = 0;
        for (int ti = 0; ti < T; ti++) {
            for (int zi = 0; zi < Z; zi++) {
                for (int ci = 0; ci < C; ci++) {
                    w.writePlane(planeIdx++, ci, zi, ti, plane(ci, zi, order));
                }
            }
        }
    }

    private void assertRoundTrip(Path store) throws Exception {
        Array array = Array.open(store.resolve("0").resolve("0"));
        ucar.ma2.Array all = array.read(
                new long[]{0, 0, 0, 0, 0}, new long[]{T, C, Z, Y, X});
        int i = 0;
        for (int t = 0; t < T; t++)
            for (int c = 0; c < C; c++)
                for (int z = 0; z < Z; z++)
                    for (int y = 0; y < Y; y++)
                        for (int x = 0; x < X; x++) {
                            int got = all.getShort(i++) & 0xFFFF;
                            assertEquals(value(c, z, y, x), got,
                                    "c=" + c + " z=" + z + " y=" + y + " x=" + x);
                        }
    }

    @Test
    void roundTripNoCodec(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("out.zarr");
        try (var w = new ZarrWriter()) {
            w.open(store, omeXml(false), "none");
            writeAll(w, ByteOrder.LITTLE_ENDIAN);
        }
        assertRoundTrip(store);
    }

    @Test
    void roundTripZstd(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("out.zarr");
        try (var w = new ZarrWriter()) {
            w.open(store, omeXml(false), "zstd");
            writeAll(w, ByteOrder.LITTLE_ENDIAN);
        }
        assertRoundTrip(store);
    }

    @Test
    void bigEndianSourceRoundTrips(@TempDir Path dir) throws Exception {
        // Source bytes are big-endian; on-disk zarr is little-endian.  The
        // logical values must survive the re-encoding.
        Path store = dir.resolve("out.zarr");
        try (var w = new ZarrWriter()) {
            w.open(store, omeXml(true), "none");
            writeAll(w, ByteOrder.BIG_ENDIAN);
        }
        assertRoundTrip(store);
    }

    @Test
    void writesBioformats2rawLayoutAndNgffMetadata(@TempDir Path dir)
            throws Exception {
        Path store = dir.resolve("out.zarr");
        try (var w = new ZarrWriter()) {
            w.open(store, omeXml(false), "none");
            writeAll(w, ByteOrder.LITTLE_ENDIAN);
        }
        assertTrue(Files.exists(store.resolve("zarr.json")), "root group");
        assertTrue(Files.exists(store.resolve("OME").resolve("METADATA.ome.xml")),
                "OME-XML sidecar");
        assertTrue(Files.exists(store.resolve("0").resolve("zarr.json")),
                "image group");
        assertTrue(Files.exists(store.resolve("0").resolve("0").resolve("zarr.json")),
                "array");

        String rootJson = Files.readString(store.resolve("zarr.json"));
        assertTrue(rootJson.contains("bioformats2raw.layout"), rootJson);

        String imageJson = Files.readString(store.resolve("0").resolve("zarr.json"));
        assertTrue(imageJson.contains("multiscales"), imageJson);
        assertTrue(imageJson.contains("\"0.5\""), imageJson);
        assertTrue(imageJson.contains("micrometer"), imageJson);

        String sidecar = Files.readString(
                store.resolve("OME").resolve("METADATA.ome.xml"));
        assertTrue(sidecar.contains("Pixels"), "sidecar holds the OME-XML");
    }

    @Test
    void refusesExistingOutput(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("out.zarr");
        Files.createDirectories(store);
        try (var w = new ZarrWriter()) {
            var ex = assertThrows(IOException.class,
                    () -> w.open(store, omeXml(false), "none"));
            assertTrue(ex.getMessage().contains("already exists"));
        }
    }

    @Test
    void refusesMultiByteWithoutEndianness(@TempDir Path dir) {
        String xml = omeXml(false).replace(" BigEndian=\"false\"", "");
        try (var w = new ZarrWriter()) {
            var ex = assertThrows(Exception.class,
                    () -> w.open(dir.resolve("out.zarr"), xml, "none"));
            assertTrue(ex.getMessage().toLowerCase().contains("byte order")
                    || ex.getMessage().toLowerCase().contains("bigendian"),
                    ex.getMessage());
        }
    }

    @Test
    void bloscUsesLz4AndByteShuffle(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("out.zarr");
        try (var w = new ZarrWriter()) {
            w.open(store, omeXml(false), "blosc:7");
            writeAll(w, ByteOrder.LITTLE_ENDIAN);
        }
        assertRoundTrip(store);
        String arr = Files.readString(
                store.resolve("0").resolve("0").resolve("zarr.json"))
                .replaceAll("\\s", "");
        assertTrue(arr.contains("\"cname\":\"lz4\""), arr);
        assertTrue(arr.contains("\"shuffle\":\"shuffle\""), arr);
        assertTrue(arr.contains("\"clevel\":7"), arr);
    }

    // ---- channel metadata (omero block) ----

    @Test
    void channelNamesAndColorsSurfacedInOmero(@TempDir Path dir) throws Exception {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <OME xmlns="http://www.openmicroscopy.org/Schemas/OME/2016-06">
              <Image ID="Image:0" Name="t">
                <Pixels ID="Pixels:0" DimensionOrder="XYCZT" Type="uint16"
                  BigEndian="false" SizeX="%d" SizeY="%d" SizeZ="%d" SizeC="2" SizeT="%d">
                  <Channel ID="Channel:0:0" Name="DAPI" Color="65535" SamplesPerPixel="1"/>
                  <Channel ID="Channel:0:1" Name="GFP" Color="16711935" SamplesPerPixel="1"/>
                </Pixels>
              </Image>
            </OME>
            """.formatted(X, Y, Z, T);
        Path store = dir.resolve("out.zarr");
        try (var w = new ZarrWriter()) {
            w.open(store, xml, "none");   // open creates the group metadata
        }
        String img = Files.readString(store.resolve("0").resolve("zarr.json"))
                .replaceAll("\\s", "");
        assertTrue(img.contains("\"omero\""), img);
        assertTrue(img.contains("\"label\":\"DAPI\""), img);
        assertTrue(img.contains("\"label\":\"GFP\""), img);
        assertTrue(img.contains("\"color\":\"0000FF\""), img);   // blue (RGBA 65535)
        assertTrue(img.contains("\"color\":\"00FF00\""), img);   // green
        // image name surfaced in both multiscales and omero
        assertTrue(img.contains("\"name\":\"t\""), img);
    }

    @Test
    void noChannelMetadataOmitsOmero(@TempDir Path dir) throws Exception {
        // The default test OME-XML has channels with no Name/Color → no omero.
        Path store = dir.resolve("out.zarr");
        try (var w = new ZarrWriter()) {
            w.open(store, omeXml(false), "none");
        }
        String img = Files.readString(store.resolve("0").resolve("zarr.json"));
        assertFalse(img.contains("omero"), img);
    }

    // ---- sharding policy ----

    @Test
    void shardPolicyKeepsLargePlanesPerFile() {
        // plane already > 1 MB → one plane per file (shardZ = 1)
        int[] s = ZarrWriter.computeShardDepths(
                new long[]{2L << 20}, new int[]{100}, new long[]{1},
                ZarrWriter.MAX_FILES);
        assertEquals(1, s[0]);
    }

    @Test
    void shardPolicyWholeSmallVolume() {
        // 10 planes × 100 KB ≈ 1 MB volume (< 4 MB) → whole volume in one shard
        int[] s = ZarrWriter.computeShardDepths(
                new long[]{100 * 1024}, new int[]{10}, new long[]{1},
                ZarrWriter.MAX_FILES);
        assertEquals(10, s[0]);
    }

    @Test
    void shardPolicyPowerOfTwoBlock() {
        // 200 planes × 100 KB = ~20 MB volume → shard = 16 planes (8×100KB<1MB≤16×)
        int[] s = ZarrWriter.computeShardDepths(
                new long[]{100 * 1024}, new int[]{200}, new long[]{1},
                ZarrWriter.MAX_FILES);
        assertEquals(16, s[0]);
    }

    @Test
    void shardPolicyHonorsFileCap() {
        // 200 Z × 100k (t·c) volumes would be millions of files at shardZ=16;
        // the cap coarsens shards up to whole volumes (200) to drop under 128k.
        int[] s = ZarrWriter.computeShardDepths(
                new long[]{100 * 1024}, new int[]{200}, new long[]{100_000},
                ZarrWriter.MAX_FILES);
        assertEquals(200, s[0]);
        assertTrue((long) 100_000 * ((200 + s[0] - 1) / s[0]) <= ZarrWriter.MAX_FILES);
    }

    // ---- suggested planes per shard (fitShardPlanes) ----

    @Test
    void fitClampsToVolumeBounds() {
        // k below 1 or n <= 1 → one plane per shard; k at or above n → whole volume.
        assertEquals(1, ZarrWriter.fitShardPlanes(50, 1));
        assertEquals(1, ZarrWriter.fitShardPlanes(50, 0));
        assertEquals(1, ZarrWriter.fitShardPlanes(1, 10));
        assertEquals(50, ZarrWriter.fitShardPlanes(50, 50));
        assertEquals(50, ZarrWriter.fitShardPlanes(50, 999));
    }

    @Test
    void fitExactDivisorIsUnchanged() {
        // k that already divides n cleanly is returned as-is.
        assertEquals(25, ZarrWriter.fitShardPlanes(100, 25));
        assertEquals(20, ZarrWriter.fitShardPlanes(100, 20));
        assertEquals(10, ZarrWriter.fitShardPlanes(100, 10));
    }

    @Test
    void fitPrefersLowOvershootDivisor() {
        // n=100,k=30: v=4→k'=25 (covers 100), u=3→k''=34 (covers 102).
        // v*k' < u*k'' → pick the exact-fitting 25.
        assertEquals(25, ZarrWriter.fitShardPlanes(100, 30));
        // n=100,k=33: 25 (covers 100) still beats 34 (covers 102) on the first
        // rule, even though 34 is numerically closer — the exact fit wins.
        assertEquals(25, ZarrWriter.fitShardPlanes(100, 33));
    }

    @Test
    void fitTakesCloserCandidateWhenSpaceTies() {
        // n=100,k=40: v=3→k'=34 (covers 102), u=2→k''=50 (covers 100).
        // u*k'' <= v*k', and 34 is the closer ratio, so the weighed tie-break
        // keeps 34 rather than jumping to 50.
        assertEquals(34, ZarrWriter.fitShardPlanes(100, 40));
    }

    @Test
    void fitResultAlwaysCoversVolume() {
        // Whatever it picks, ceil(n/k*) shards of k* planes must cover n, and
        // k* must stay within [1, n].
        for (int n = 2; n <= 64; n++) {
            for (int k = 1; k <= n + 5; k++) {
                int ks = ZarrWriter.fitShardPlanes(n, k);
                assertTrue(ks >= 1 && ks <= n,
                        "n=" + n + " k=" + k + " → " + ks + " out of [1," + n + "]");
                int shards = (n + ks - 1) / ks;
                assertTrue((long) shards * ks >= n,
                        "n=" + n + " k=" + k + " → " + ks + " fails to cover");
            }
        }
    }

    @Test
    void suggestionOverridesFileCap() {
        // The same pathological 200 Z × 100k (t·c) case the auto policy coarsens
        // all the way to 200 (shardPolicyHonorsFileCap): an explicit suggestion
        // of 1 is honored (stays 1) even though the file count blows past the cap.
        int[] s = ZarrWriter.computeShardDepths(
                new long[]{100 * 1024}, new int[]{200}, new long[]{100_000},
                ZarrWriter.MAX_FILES, 1);
        assertEquals(1, s[0]);
        assertTrue((long) 100_000 * 200 > ZarrWriter.MAX_FILES);
    }

    @Test
    void capOverrideWarningOnlyWhenExceeded() {
        assertNull(ZarrWriter.capOverrideWarning(100, 128));
        assertNull(ZarrWriter.capOverrideWarning(128, 128));   // at the cap is fine
        String w = ZarrWriter.capOverrideWarning(500, 128);
        assertNotNull(w);
        assertTrue(w.contains("500") && w.contains("128"), w);
    }

    @Test
    void shardPolicyHonorsSuggestion() {
        // 200 Z, plane well under 1 MB: the byte heuristic would pick 16, but a
        // suggestion of 30 fits to 29 (v=7 shards → ceil(200/7)=29, covering 203,
        // a tighter fit than u=6 → 34 covering 204).
        int[] s = ZarrWriter.computeShardDepths(
                new long[]{100 * 1024}, new int[]{200}, new long[]{1},
                ZarrWriter.MAX_FILES, 30);
        assertEquals(29, s[0]);
    }

    @Test
    void indexOnlyWriteUnsupported(@TempDir Path dir) throws Exception {
        try (var w = new ZarrWriter()) {
            w.open(dir.resolve("out.zarr"), omeXml(false), "none");
            w.setSeries(0);
            assertThrows(UnsupportedOperationException.class,
                    () -> w.writePlane(0, new byte[Y * X * 2]));
        }
    }
}
