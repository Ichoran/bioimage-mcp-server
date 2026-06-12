package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that {@link BioImageSocketService} binds its Unix-domain socket
 * owner-only (0600) so another local user cannot connect — the filesystem
 * identity that makes the socket transport user-only without a token.
 */
@Timeout(20)
class SocketPermsTest {

    @Test
    void socketFileIsOwnerOnly(@TempDir Path dir) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions only enforced on Linux/macOS");

        Supplier<ImageReader> fake = () -> FakeImageReader.builder()
                .littleEndian(true)
                .addSeries(FakeImageReader.FakeSeries.simple(4, 3, 1, 1, 1, PixelType.UINT8))
                .build();
        var svc = BioImageService.builder()
                .allow(dir.toString())
                .readerFactory(fake)
                .build();

        Path sock = dir.resolve("perms.sock");
        var server = BioImageSocketService.create(svc, sock);
        Thread.ofVirtual().start(() -> {
            try { server.serve(sock); } catch (Exception ignored) { /* test teardown */ }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(sock) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(sock), "server socket did not appear");

        // bind() creates the socket file at the process umask; the chmod to 0600
        // follows by a hair (the 0700 parent dir is the real guarantee in the
        // window), so poll until the owner-only mode has settled.
        Set<PosixFilePermission> ownerOnly =
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Set<PosixFilePermission> perms = null;
        long permDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < permDeadline) {
            perms = Files.getPosixFilePermissions(sock);
            if (perms.equals(ownerOnly)) break;
            Thread.sleep(10);
        }
        assertEquals(ownerOnly, perms, "socket must be 0600 (owner-only)");
    }
}
