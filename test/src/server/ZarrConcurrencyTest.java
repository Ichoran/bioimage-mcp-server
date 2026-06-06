package lab.kerrr.mcpbio.bioimageserver;

import dev.zarr.zarrjava.v3.Array;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that many disjoint <b>shard blocks</b> (the unit the parallel
 * exporter hands off — each a whole shard) can be written to a single
 * {@link ZarrWriter} array <b>concurrently</b> and still round-trip byte-exact.
 *
 * <p>This mirrors the real parallel write pattern: distinct shard-aligned
 * blocks map to distinct shard files, so concurrent writes never touch the same
 * file.  (Writing individual <i>planes</i> of the same shard concurrently would
 * be unsafe — which is exactly why the exporter's unit of work is a whole
 * shard.)  These dimensions (multichannel, small planes) make the planner pick
 * channel-bundled {@code [1,C,Z]} shards, so the concurrency is across T.
 */
class ZarrConcurrencyTest {

    private static final int X = 24, Y = 20, Z = 8, C = 4, T = 6;

    private static int value(int c, int z, int t, int y, int x) {
        return (c * 7919 + z * 131 + t * 17 + y * 24 + x) & 0xFFFF;
    }

    private static String omeXml() {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<OME xmlns=\"http://www.openmicroscopy.org/Schemas/OME/2016-06\">"
            + "<Image ID=\"Image:0\" Name=\"t\"><Pixels ID=\"Pixels:0\" "
            + "DimensionOrder=\"XYCZT\" Type=\"uint16\" BigEndian=\"false\" "
            + "SizeX=\"" + X + "\" SizeY=\"" + Y + "\" SizeZ=\"" + Z + "\" "
            + "SizeC=\"" + C + "\" SizeT=\"" + T + "\">"
            + "<Channel ID=\"Channel:0:0\" SamplesPerPixel=\"1\"/>"
            + "</Pixels></Image></OME>");
    }

    /** One shard block [nt,nc,nz,Y,X] in TCZYX C-order, from (tb,cb,zb). */
    private static byte[] shardBlock(int tb, int cb, int zb, int nt, int nc, int nz) {
        ByteBuffer buf = ByteBuffer.allocate(nt * nc * nz * Y * X * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int ti = 0; ti < nt; ti++)
            for (int ci = 0; ci < nc; ci++)
                for (int zi = 0; zi < nz; zi++)
                    for (int y = 0; y < Y; y++)
                        for (int x = 0; x < X; x++)
                            buf.putShort((short) value(cb + ci, zb + zi, tb + ti, y, x));
        return buf.array();
    }

    @Test
    void concurrentDisjointShardWritesRoundTrip(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("out.zarr");
        try (var w = new ZarrWriter()) {
            w.open(store, omeXml(), "zstd");
            w.setSeries(0);

            int[] sh = w.preferredBlockShape(0);
            int bT = sh[0], bC = sh[1], bZ = sh[2];

            int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
            try (var pool = Executors.newFixedThreadPool(threads)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int tb = 0; tb < T; tb += bT) {
                    for (int cb = 0; cb < C; cb += bC) {
                        for (int zb = 0; zb < Z; zb += bZ) {
                            int nt = Math.min(bT, T - tb);
                            int nc = Math.min(bC, C - cb);
                            int nz = Math.min(bZ, Z - zb);
                            int ftb = tb, fcb = cb, fzb = zb,
                                fnt = nt, fnc = nc, fnz = nz;
                            futures.add(pool.submit(() -> {
                                try {
                                    // Each task writes one whole shard.
                                    w.writeShardBlock(ftb, fcb, fzb, fnt, fnc, fnz,
                                            shardBlock(ftb, fcb, fzb, fnt, fnc, fnz));
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }));
                        }
                    }
                }
                for (var f : futures) f.get();  // surface any worker exception
            }
        }

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
                            assertEquals(value(c, z, t, y, x), got,
                                    "t=" + t + " c=" + c + " z=" + z
                                    + " y=" + y + " x=" + x);
                        }
    }
}
