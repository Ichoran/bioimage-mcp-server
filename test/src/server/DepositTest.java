package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BioImageService#deposit}.  Uses {@link FakeImageReader},
 * whose pixel value at {@code (x,y,c,z,t)} is the deterministic
 * {@code y*sizeX + x + c*7 + z*13 + t*31} (mod type range), so the exact bytes
 * landing in the target region can be predicted independently.
 */
class DepositTest {

    private static final int X = 4, Y = 3, Z = 2, C = 1, T = 1;

    /** Service whose reader is a fake uint8 volume; source path access allowed. */
    private static BioImageService serviceFor(Path allowDir, int sc) {
        Supplier<ImageReader> fake = () -> FakeImageReader.builder()
                .littleEndian(true)
                .addSeries(FakeImageReader.FakeSeries.simple(
                        X, Y, Z, sc, T, PixelType.UINT8))
                .build();
        return BioImageService.builder()
                .allow(allowDir.toString())
                .readerFactory(fake)
                .build();
    }

    /** Create a real (empty) file so PathAccessControl can canonicalize it. */
    private static Path touch(Path dir, String name) throws Exception {
        Path p = dir.resolve(name);
        Files.createFile(p);
        return p;
    }

    /** Pre-allocate the client-owned region to exactly {@code size} bytes. */
    private static Path region(Path dir, String name, long size) throws Exception {
        Path p = dir.resolve(name);
        try (var raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.setLength(size);
        }
        return p;
    }

    private static Map<String, Object> target(Path path, long capacity) {
        var t = new LinkedHashMap<String, Object>();
        t.put("kind", "file");
        t.put("path", path.toString());
        t.put("capacity_bytes", capacity);
        return t;
    }

    private static int expected(int x, int y, int c, int z, int t) {
        return (int) ((long) y * X + x + c * 7L + z * 13L + t * 31L) & 0xFF;
    }

    /** Base deposit args selecting the whole volume (all channels/z/t). */
    private static Map<String, Object> argsFor(Path src) {
        var a = new LinkedHashMap<String, Object>();
        a.put("path", src.toString());
        a.put("channels", ":");
        a.put("z", ":");
        a.put("t", ":");
        return a;
    }

    @Test
    void depositWritesExpectedBytesInTCZYXOrder(@TempDir Path dir) throws Exception {
        var service = serviceFor(dir, C);
        var src = touch(dir, "src.fake");
        long total = (long) X * Y * Z * C * T;          // uint8 ⇒ 1 byte/sample
        var tgt = region(dir, "shm.bin", total);

        var args = argsFor(src);
        args.put("target", target(tgt, total));

        var result = service.deposit(args);
        var d = assertSuccess(result);

        assertEquals(total, d.totalBytes());
        assertEquals((long) X * Y, d.planeBytes());
        assertEquals(X, d.sizeX());
        assertEquals(Y, d.sizeY());
        assertEquals(C, d.sizeC());
        assertEquals(Z, d.sizeZ());
        assertEquals(T, d.sizeT());
        assertEquals("uint8", d.pixelType());

        byte[] bytes = Files.readAllBytes(tgt);
        assertEquals(total, bytes.length);
        for (int t = 0; t < T; t++) {
            for (int c = 0; c < C; c++) {
                for (int z = 0; z < Z; z++) {
                    long planeOffset = (((long) t * C + c) * Z + z) * X * Y;
                    for (int y = 0; y < Y; y++) {
                        for (int x = 0; x < X; x++) {
                            int got = bytes[(int) (planeOffset + (long) y * X + x)] & 0xFF;
                            assertEquals(expected(x, y, c, z, t), got,
                                    "byte at (t=" + t + ",c=" + c + ",z=" + z
                                    + ",y=" + y + ",x=" + x + ")");
                        }
                    }
                }
            }
        }
    }

    @Test
    void multiChannelVolumeRespectsAxisOrder(@TempDir Path dir) throws Exception {
        int sc = 3;
        var service = serviceFor(dir, sc);
        var src = touch(dir, "src.fake");
        long total = (long) X * Y * Z * sc * T;
        var tgt = region(dir, "shm.bin", total);

        var args = argsFor(src);
        args.put("target", target(tgt, total));   // no channel arg ⇒ all channels

        var d = assertSuccess(service.deposit(args));
        assertEquals(sc, d.sizeC());

        byte[] bytes = Files.readAllBytes(tgt);
        // Spot-check the NGFF [t,c,z,y,x] order: C is OUTER of Z, so each
        // channel's full Z-stack is a contiguous block (channel-major).
        for (int c = 0; c < sc; c++) {
            for (int z = 0; z < Z; z++) {
                long planeOffset = (((long) 0 * sc + c) * Z + z) * X * Y;
                assertEquals(expected(0, 0, c, z, 0),
                        bytes[(int) planeOffset] & 0xFF,
                        "plane origin (c=" + c + ",z=" + z + ")");
            }
        }
    }

    @Test
    void dryRunReturnsDescriptorWithoutTouchingTarget(@TempDir Path dir)
            throws Exception {
        var service = serviceFor(dir, C);
        var src = touch(dir, "src.fake");
        var tgt = region(dir, "shm.bin", 4);   // deliberately too small

        var args = argsFor(src);
        args.put("dry_run", true);

        var d = assertSuccess(service.deposit(args));
        assertEquals((long) X * Y * Z * C * T, d.totalBytes());
        // Target untouched (still the 4 zero bytes we allocated).
        byte[] bytes = Files.readAllBytes(tgt);
        assertEquals(4, bytes.length);
        for (byte b : bytes) assertEquals(0, b);
    }

    @Test
    void capacityTooSmallFailsAndWritesNothing(@TempDir Path dir) throws Exception {
        var service = serviceFor(dir, C);
        var src = touch(dir, "src.fake");
        long total = (long) X * Y * Z * C * T;
        var tgt = region(dir, "shm.bin", total);   // physically big enough...

        var args = argsFor(src);
        args.put("target", target(tgt, total - 1));   // ...but declared capacity short

        var f = assertFailure(service.deposit(args));
        assertEquals(ToolResult.ErrorKind.INVALID_ARGUMENT, f.kind());
        // Region never written — still all zeros.
        for (byte b : Files.readAllBytes(tgt)) assertEquals(0, b);
    }

    @Test
    void sourceOutsideAllowedPathsIsDenied(@TempDir Path dir) throws Exception {
        var service = serviceFor(dir, C);
        // A real file that exists but is NOT under the allowed dir.
        Path outside = Files.createTempFile("deposit-outside", ".fake");
        try {
            long total = (long) X * Y * Z * C * T;
            var tgt = region(dir, "shm.bin", total);
            var args = argsFor(outside);
            args.put("target", target(tgt, total));

            var f = assertFailure(service.deposit(args));
            assertEquals(ToolResult.ErrorKind.ACCESS_DENIED, f.kind());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    // ---- helpers ----

    private static DepositDescriptor assertSuccess(ToolResult<DepositDescriptor> r) {
        if (r instanceof ToolResult.Success<DepositDescriptor> s) {
            return s.value();
        }
        var f = (ToolResult.Failure<DepositDescriptor>) r;
        return fail("expected success, got " + f.kind() + ": " + f.message());
    }

    private static ToolResult.Failure<DepositDescriptor> assertFailure(
            ToolResult<DepositDescriptor> r) {
        if (r instanceof ToolResult.Failure<DepositDescriptor> f) {
            return f;
        }
        return fail("expected failure, got success");
    }
}
