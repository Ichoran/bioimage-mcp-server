package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.ExportToNgffTool.Codec;
import lab.kerrr.mcpbio.bioimageserver.ExportToNgffTool.NgffResult;
import lab.kerrr.mcpbio.bioimageserver.FakeImageReader.FakeSeries;
import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;
import lab.kerrr.mcpbio.bioimageserver.ToolResult.ErrorKind;

import dev.zarr.zarrjava.v3.Array;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end tests: FakeImageReader → ExportToNgffTool → read back with zarr-java. */
class ExportToNgffToolTest {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final long MAX_BYTES = 256L * 1024 * 1024;
    static final int SX = 16, SY = 16;

    /** Shared writer pool for the test class (mirrors the server-wide pool). */
    static final BlockPool POOL = new BlockPool(4);

    @AfterAll
    static void closePool() {
        POOL.close();
    }

    /** FakeImageReader's deterministic value for a uint16 source. */
    private static int rawValue(int x, int y, int c, int z, int t) {
        return (int) (((long) y * SX + x + c * 7L + z * 13L + t * 31L) % 65536);
    }

    private static Supplier<ImageReader> reader(int sizeC, int sizeZ, int sizeT) {
        return () -> FakeImageReader.builder()
                .addSeries(FakeSeries.simple(
                        SX, SY, sizeZ, sizeC, sizeT, PixelType.UINT16))
                .build();
    }

    private static NgffResult exportOk(
            Supplier<ImageReader> rf, Path out,
            Slice channels, Slice z, Slice t) {
        return exportOk(rf, out, channels, z, t, Codec.NONE, null);
    }

    private static NgffResult exportOk(
            Supplier<ImageReader> rf, Path out,
            Slice channels, Slice z, Slice t, Codec codec, Integer level) {
        var request = ExportToNgffTool.Request.of(
                "/input.tif", out.toString(), null,
                channels, z, t, codec, level, null, TIMEOUT, MAX_BYTES);
        var result = ExportToNgffTool.execute(
                request, PathValidator.allowAll(), rf, ZarrWriter::new, POOL);
        if (result instanceof ToolResult.Failure<NgffResult> f) {
            fail("expected success, got " + f.kind() + ": " + f.message());
        }
        return ((ToolResult.Success<NgffResult>) result).value();
    }

    /** Read series 0 level 0 back as a [t,c,z,y,x] array. */
    private static ucar.ma2.Array readBack(
            Path store, int t, int c, int z) throws Exception {
        Array array = Array.open(store.resolve("0").resolve("0"));
        return array.read(new long[]{0, 0, 0, 0, 0},
                new long[]{t, c, z, SY, SX});
    }

    @Test
    void fullExportRoundTrip(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("out.zarr");
        int C = 2, Z = 2, T = 2;
        NgffResult r = exportOk(reader(C, Z, T), store,
                Slice.all(), Slice.all(), Slice.all());

        assertEquals(1, r.seriesExported());
        assertEquals(C, r.channelsPerSeries());
        assertEquals(Z, r.zSlicesPerSeries());
        assertEquals(T, r.timepointsPerSeries());
        assertEquals((long) C * Z * T, r.totalPlanesWritten());
        assertTrue(r.bytesWritten() > 0);
        assertTrue(r.omeXmlPresent());

        ucar.ma2.Array all = readBack(store, T, C, Z);
        int i = 0;
        for (int t = 0; t < T; t++)
            for (int c = 0; c < C; c++)
                for (int z = 0; z < Z; z++)
                    for (int y = 0; y < SY; y++)
                        for (int x = 0; x < SX; x++) {
                            int got = all.getShort(i++) & 0xFFFF;
                            assertEquals(rawValue(x, y, c, z, t), got,
                                    "t=" + t + " c=" + c + " z=" + z
                                    + " y=" + y + " x=" + x);
                        }
    }

    @Test
    void nonContiguousChannelSubsetMapsCorrectly(@TempDir Path dir)
            throws Exception {
        // Source has 3 channels; export channels 0 and 2.  Output channel 0/1
        // must hold SOURCE channels 0/2.
        Path store = dir.resolve("out.zarr");
        NgffResult r = exportOk(reader(3, 1, 1), store,
                Slice.parse("0,2", "channels"), Slice.all(), Slice.all());
        assertEquals(2, r.channelsPerSeries());

        ucar.ma2.Array all = readBack(store, 1, 2, 1);
        int[] sourceChannel = {0, 2};
        int i = 0;
        for (int c = 0; c < 2; c++)
            for (int z = 0; z < 1; z++)
                for (int y = 0; y < SY; y++)
                    for (int x = 0; x < SX; x++) {
                        int got = all.getShort(i++) & 0xFFFF;
                        assertEquals(rawValue(x, y, sourceChannel[c], 0, 0), got,
                                "outC=" + c + " y=" + y + " x=" + x);
                    }
    }

    @Test
    void zSubsetRoundTrips(@TempDir Path dir) throws Exception {
        // 5 Z; export z 1:4 (source z 1,2,3 → output z 0,1,2).
        Path store = dir.resolve("out.zarr");
        NgffResult r = exportOk(reader(1, 5, 1), store,
                Slice.all(), Slice.parse("1:4", "z"), Slice.all());
        assertEquals(3, r.zSlicesPerSeries());

        ucar.ma2.Array all = readBack(store, 1, 1, 3);
        int i = 0;
        for (int z = 0; z < 3; z++)
            for (int y = 0; y < SY; y++)
                for (int x = 0; x < SX; x++) {
                    int got = all.getShort(i++) & 0xFFFF;
                    assertEquals(rawValue(x, y, 0, z + 1, 0), got);
                }
    }

    @Test
    void reportsActualShardDepthByDefault(@TempDir Path dir) {
        // A small 10-plane volume (16×16 uint16 ≈ 5 KB) fits in one shard, so
        // the auto policy reports the whole volume — always reported, no hint.
        Path store = dir.resolve("auto.zarr");
        NgffResult r = exportOk(reader(1, 10, 1), store,
                Slice.all(), Slice.all(), Slice.all());
        assertArrayEquals(new int[]{10}, r.shardPlanesPerSeries());
    }

    @Test
    void suggestedPlanesPerShardIsReported(@TempDir Path dir) throws Exception {
        // Suggest 3 planes/shard on a 10-plane volume → fits to 3 (4 shards),
        // reported back, and the data still round-trips.
        Path store = dir.resolve("hint.zarr");
        var request = ExportToNgffTool.Request.of(
                "/input.tif", store.toString(), null,
                Slice.all(), Slice.all(), Slice.all(),
                Codec.NONE, null, 3, TIMEOUT, MAX_BYTES);
        var result = ExportToNgffTool.execute(
                request, PathValidator.allowAll(), reader(1, 10, 1),
                ZarrWriter::new, POOL);
        var r = ((ToolResult.Success<NgffResult>) result).value();
        assertArrayEquals(new int[]{3}, r.shardPlanesPerSeries());

        ucar.ma2.Array all = readBack(store, 1, 1, 10);
        int i = 0;
        for (int z = 0; z < 10; z++)
            for (int y = 0; y < SY; y++)
                for (int x = 0; x < SX; x++) {
                    int got = all.getShort(i++) & 0xFFFF;
                    assertEquals(rawValue(x, y, 0, z, 0), got);
                }
    }

    @Test
    void layoutWarningsSurfaceInResult(@TempDir Path dir) {
        // A writer that reports a layout warning (as ZarrWriter does when a
        // suggestion overrides the file-count cap) must have it land in the
        // result's warnings — the cap override is honored but never silent.
        final String MSG = "synthetic layout warning";
        Supplier<ImageWriter> wf = () -> new ImageWriter() {
            final ZarrWriter d = new ZarrWriter();
            public void open(Path p, String x, String c) throws IOException {
                d.open(p, x, c);
            }
            public void setSeries(int s) throws IOException { d.setSeries(s); }
            public int[] preferredBlockShape(int s) { return d.preferredBlockShape(s); }
            public void writeShardBlock(int ts, int cs, int zs, int bt, int bc,
                    int bz, byte[] dat) throws IOException {
                d.writeShardBlock(ts, cs, zs, bt, bc, bz, dat);
            }
            public void writePlane(int i, byte[] dat) throws IOException {
                d.writePlane(i, dat);
            }
            public java.util.List<String> layoutWarnings() {
                return java.util.List.of(MSG);
            }
            public long getBytesWritten() { return d.getBytesWritten(); }
            public void close() throws IOException { d.close(); }
        };
        var request = ExportToNgffTool.Request.of(
                "/input.tif", dir.resolve("w.zarr").toString(), null,
                Slice.all(), Slice.all(), Slice.all(),
                Codec.NONE, null, null, TIMEOUT, MAX_BYTES);
        var result = ExportToNgffTool.execute(
                request, PathValidator.allowAll(), reader(1, 2, 1), wf, POOL);
        var r = ((ToolResult.Success<NgffResult>) result).value();
        assertTrue(r.warnings().contains(MSG), r.warnings().toString());
    }

    @Test
    void suggestionBelowOneRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ExportToNgffTool.Request.of(
                        "/in.tif", "/out.zarr", null,
                        Slice.all(), Slice.all(), Slice.all(),
                        Codec.NONE, null, 0, TIMEOUT, MAX_BYTES));
        assertTrue(ex.getMessage().contains("at least 1"), ex.getMessage());
    }

    @Test
    void timeSeriesShardsAcrossTime(@TempDir Path dir) throws Exception {
        // 1 channel, 1 Z, 8 timepoints with a suggestion of 4 → shard across T
        // (no Z to bundle), 4 timepoints per shard; data round-trips.
        Path store = dir.resolve("ts.zarr");
        var request = ExportToNgffTool.Request.of(
                "/input.tif", store.toString(), null,
                Slice.all(), Slice.all(), Slice.all(),
                Codec.NONE, null, 4, TIMEOUT, MAX_BYTES);
        var result = ExportToNgffTool.execute(
                request, PathValidator.allowAll(), reader(1, 1, 8),
                ZarrWriter::new, POOL);
        var r = ((ToolResult.Success<NgffResult>) result).value();
        assertArrayEquals(new int[]{4}, r.shardPlanesPerSeries());

        ucar.ma2.Array all = readBack(store, 8, 1, 1);
        int i = 0;
        for (int t = 0; t < 8; t++)
            for (int y = 0; y < SY; y++)
                for (int x = 0; x < SX; x++) {
                    int got = all.getShort(i++) & 0xFFFF;
                    assertEquals(rawValue(x, y, 0, 0, t), got);
                }
    }

    @Test
    void multichannelBundlesIntoWholeVolumeShard(@TempDir Path dir) throws Exception {
        // 3 channels, 4 Z; suggestion ~ C*Z=12 → one shard per timepoint
        // spanning all channels and Z (12 planes); data round-trips.
        Path store = dir.resolve("cz.zarr");
        var request = ExportToNgffTool.Request.of(
                "/input.tif", store.toString(), null,
                Slice.all(), Slice.all(), Slice.all(),
                Codec.NONE, null, 12, TIMEOUT, MAX_BYTES);
        var result = ExportToNgffTool.execute(
                request, PathValidator.allowAll(), reader(3, 4, 2),
                ZarrWriter::new, POOL);
        var r = ((ToolResult.Success<NgffResult>) result).value();
        assertArrayEquals(new int[]{12}, r.shardPlanesPerSeries());

        ucar.ma2.Array all = readBack(store, 2, 3, 4);
        int i = 0;
        for (int t = 0; t < 2; t++)
            for (int c = 0; c < 3; c++)
                for (int z = 0; z < 4; z++)
                    for (int y = 0; y < SY; y++)
                        for (int x = 0; x < SX; x++) {
                            int got = all.getShort(i++) & 0xFFFF;
                            assertEquals(rawValue(x, y, c, z, t), got);
                        }
    }

    @Test
    void writesValidNgffStructure(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("out.zarr");
        exportOk(reader(1, 1, 1), store, Slice.all(), Slice.all(), Slice.all());
        assertTrue(Files.exists(store.resolve("zarr.json")));
        assertTrue(Files.exists(store.resolve("OME").resolve("METADATA.ome.xml")));
        String imageJson = Files.readString(
                store.resolve("0").resolve("zarr.json"));
        assertTrue(imageJson.contains("multiscales"));
        assertTrue(imageJson.contains("\"0.5\""));
    }

    @Test
    void refusesExistingOutput(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("out.zarr");
        Files.createDirectories(store);
        var request = ExportToNgffTool.Request.of(
                "/input.tif", store.toString(), null,
                Slice.all(), Slice.all(), Slice.all(),
                Codec.NONE, null, null, TIMEOUT, MAX_BYTES);
        var result = ExportToNgffTool.execute(
                request, PathValidator.allowAll(), reader(1, 1, 1), ZarrWriter::new, POOL);
        assertInstanceOf(ToolResult.Failure.class, result);
        assertEquals(ErrorKind.IO_ERROR,
                ((ToolResult.Failure<NgffResult>) result).kind());
    }

    @Test
    void accessDeniedOnOutput(@TempDir Path dir) {
        PathValidator selective = path ->
                path.contains("out.zarr")
                        ? new AccessResult.Denied("no write")
                        : new AccessResult.Allowed(Path.of(path));
        var request = ExportToNgffTool.Request.of(
                "/input.tif", dir.resolve("out.zarr").toString(), null,
                Slice.all(), Slice.all(), Slice.all(),
                Codec.NONE, null, null, TIMEOUT, MAX_BYTES);
        var result = ExportToNgffTool.execute(
                request, selective, selective, reader(1, 1, 1), ZarrWriter::new, POOL);
        assertInstanceOf(ToolResult.Failure.class, result);
        var f = (ToolResult.Failure<NgffResult>) result;
        assertEquals(ErrorKind.ACCESS_DENIED, f.kind());
        assertTrue(f.message().contains("output"));
    }

    @Test
    void byteBudgetExceeded(@TempDir Path dir) {
        // 16×16 uint16 × 2C × 2Z × 2T = 16384 bytes; budget 1000.
        var request = new ExportToNgffTool.Request(
                "/input.tif", dir.resolve("out.zarr").toString(), null,
                Slice.all(), Slice.all(), Slice.all(),
                Codec.NONE, null, null, TIMEOUT, 1000);
        var result = ExportToNgffTool.execute(
                request, PathValidator.allowAll(), reader(2, 2, 2), ZarrWriter::new, POOL);
        assertInstanceOf(ToolResult.Failure.class, result);
        var f = (ToolResult.Failure<NgffResult>) result;
        assertEquals(ErrorKind.INVALID_ARGUMENT, f.kind());
        assertTrue(f.message().toLowerCase().contains("maxbytes"));
    }

    @Test
    void zstdLevelRoundTrips(@TempDir Path dir) throws Exception {
        // A fast level and a high-effort level both produce correct data.
        for (int level : new int[]{-3, 1, 19}) {
            Path store = dir.resolve("lvl" + level + ".zarr");
            NgffResult r = exportOk(reader(1, 1, 1), store,
                    Slice.all(), Slice.all(), Slice.all(), Codec.ZSTD, level);
            assertEquals(level, r.compressionLevel());

            ucar.ma2.Array all = readBack(store, 1, 1, 1);
            int i = 0;
            for (int y = 0; y < SY; y++)
                for (int x = 0; x < SX; x++) {
                    int got = all.getShort(i++) & 0xFFFF;
                    assertEquals(rawValue(x, y, 0, 0, 0), got);
                }
        }
    }

    @Test
    void gzipLevelRoundTrips(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("gz.zarr");
        NgffResult r = exportOk(reader(1, 1, 1), store,
                Slice.all(), Slice.all(), Slice.all(), Codec.GZIP, 9);
        assertEquals(9, r.compressionLevel());
        ucar.ma2.Array all = readBack(store, 1, 1, 1);
        assertEquals(rawValue(0, 0, 0, 0, 0), all.getShort(0) & 0xFFFF);
    }

    @Test
    void outOfRangeLevelRejected() {
        // zstd max is 22; 99 is out of range → caught at request validation.
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ExportToNgffTool.Request.of(
                        "/in.tif", "/out.zarr", null,
                        Slice.all(), Slice.all(), Slice.all(),
                        Codec.ZSTD, 99, null, TIMEOUT, MAX_BYTES));
        assertTrue(ex.getMessage().contains("out of range"), ex.getMessage());
    }

    @Test
    void defaultsToZstdLevel5(@TempDir Path dir) {
        // No codec, no level → zstd at an explicit, reported level 5.
        var req = ExportToNgffTool.Request.of(
                "/in.tif", dir.resolve("o.zarr").toString(), null,
                Slice.all(), Slice.all(), Slice.all(),
                null, null, null, null, null);
        assertEquals(Codec.ZSTD, req.codec());
        assertEquals(5, req.compressionLevel());
    }

    @Test
    void blandLevelLeftToCodecDefault() {
        // gzip and blosc keep null (their own defaults), not pinned to 5.
        var gz = ExportToNgffTool.Request.of(
                "/in.tif", "/o.zarr", null,
                Slice.all(), Slice.all(), Slice.all(),
                Codec.GZIP, null, null, null, null);
        assertNull(gz.compressionLevel());
        var bl = ExportToNgffTool.Request.of(
                "/in.tif", "/o.zarr", null,
                Slice.all(), Slice.all(), Slice.all(),
                Codec.BLOSC, null, null, null, null);
        assertNull(bl.compressionLevel());
    }

    @Test
    void levelOnNoneCodecRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ExportToNgffTool.Request.of(
                        "/in.tif", "/out.zarr", null,
                        Slice.all(), Slice.all(), Slice.all(),
                        Codec.NONE, 5, null, TIMEOUT, MAX_BYTES));
        assertTrue(ex.getMessage().contains("not applicable"), ex.getMessage());
    }

    // ---- error isolation: a write failure fails the op AND deletes the store ----

    /** Wraps a real ZarrWriter (so the store gets created) but fails on write. */
    private static final class FailingWriter implements ImageWriter {
        private final ZarrWriter delegate = new ZarrWriter();
        public void open(Path p, String xml, String c) throws IOException {
            delegate.open(p, xml, c);   // creates the store on disk
        }
        public void setSeries(int s) throws IOException { delegate.setSeries(s); }
        public int[] preferredBlockShape(int s) { return delegate.preferredBlockShape(s); }
        public void writePlane(int i, byte[] d) throws IOException {
            throw new IOException("simulated write failure");
        }
        public void writeShardBlock(int ts, int cs, int zs, int bt, int bc,
                int bz, byte[] d) throws IOException {
            throw new IOException("simulated write failure");
        }
        public long getBytesWritten() { return delegate.getBytesWritten(); }
        public void close() throws IOException { delegate.close(); }
    }

    @Test
    void writeErrorFailsOpAndDeletesPartialStore(@TempDir Path dir) {
        Path store = dir.resolve("out.zarr");
        var request = ExportToNgffTool.Request.of(
                "/input.tif", store.toString(), null,
                Slice.all(), Slice.all(), Slice.all(),
                Codec.NONE, null, null, TIMEOUT, MAX_BYTES);
        var result = ExportToNgffTool.execute(
                request, PathValidator.allowAll(),
                reader(2, 2, 2), FailingWriter::new, POOL);
        assertInstanceOf(ToolResult.Failure.class, result);
        var f = (ToolResult.Failure<NgffResult>) result;
        assertEquals(ErrorKind.IO_ERROR, f.kind());
        // No partial store left behind to masquerade as valid data.
        assertFalse(Files.exists(store), "partial store must be deleted");
    }
}
