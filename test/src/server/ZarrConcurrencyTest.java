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
 * Spike: verifies that many disjoint {@code (t,c,z)} planes can be written to a
 * single {@link ZarrWriter} array <b>concurrently</b> and still round-trip
 * byte-exact.  This is the safety precondition for a parallel export pool — if
 * zarr-java's {@code Array.write} were unsafe for concurrent disjoint chunks,
 * the parallel design would need a lock around the store write instead.
 */
class ZarrConcurrencyTest {

    private static final int X = 24, Y = 20, Z = 8, C = 4, T = 2;

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

    private static byte[] plane(int c, int z, int t) {
        ByteBuffer buf = ByteBuffer.allocate(Y * X * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < Y; y++)
            for (int x = 0; x < X; x++)
                buf.putShort((short) value(c, z, t, y, x));
        return buf.array();
    }

    @Test
    void concurrentDisjointPlaneWritesRoundTrip(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("out.zarr");
        try (var w = new ZarrWriter()) {
            w.open(store, omeXml(), "zstd");
            w.setSeries(0);

            int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
            try (var pool = Executors.newFixedThreadPool(threads)) {
                List<Future<?>> futures = new ArrayList<>();
                int idx = 0;
                for (int t = 0; t < T; t++) {
                    for (int z = 0; z < Z; z++) {
                        for (int c = 0; c < C; c++) {
                            int fc = c, fz = z, ft = t, fi = idx++;
                            futures.add(pool.submit(() -> {
                                try {
                                    w.writePlane(fi, fc, fz, ft, plane(fc, fz, ft));
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
