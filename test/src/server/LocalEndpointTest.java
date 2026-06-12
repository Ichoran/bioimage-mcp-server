package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link LocalEndpoint}: token generation/verification and the
 * per-user discovery descriptor.  Uses the {@code (…, Path dir)} test seam with
 * a {@link TempDir} so the tests never depend on {@code $XDG_RUNTIME_DIR}.
 */
@Timeout(10)
class LocalEndpointTest {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @Test
    void tokenIsRandomAndUrlSafe(@TempDir Path dir) {
        var a = LocalEndpoint.create("bioimage-grpc", null, true, dir);
        var b = LocalEndpoint.create("bioimage-grpc", null, true, dir);
        assertNotNull(a.token());
        // 32 bytes base64url, no padding → 43 chars, no '+' '/' '='.
        assertEquals(43, a.token().length());
        assertFalse(a.token().matches(".*[+/=].*"), "token must be URL-safe");
        assertNotEquals(a.token(), b.token(), "tokens must differ between instances");
    }

    @Test
    void noTokenWhenNotRequested(@TempDir Path dir) {
        var e = LocalEndpoint.create("bioimage-http", null, false, dir);
        assertNull(e.token());
        assertFalse(e.verify("anything"), "a token-less endpoint verifies nothing");
        assertFalse(e.verify(null));
    }

    @Test
    void verifyMatchesRejectsAndStripsBearer(@TempDir Path dir) {
        var e = LocalEndpoint.create("bioimage-grpc", null, true, dir);
        String t = e.token();
        assertTrue(e.verify(t));
        assertTrue(e.verify("Bearer " + t), "leading 'Bearer ' is stripped");
        assertFalse(e.verify(t + "x"));
        assertFalse(e.verify("Bearer wrong"));
        assertFalse(e.verify(null));
    }

    @Test
    void publishWritesPortAndToken(@TempDir Path dir) throws IOException {
        var e = LocalEndpoint.create("bioimage-grpc", null, true, dir);
        Path file = e.publish(41877);
        assertTrue(Files.exists(file));

        Map<String, Object> desc = JsonUtil.parseObject(Files.readString(file));
        assertEquals(41877, ((Number) desc.get("port")).intValue());
        assertEquals(e.token(), desc.get("token"));

        assumeTrue(POSIX, "POSIX permissions only enforced on Linux/macOS");
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE),
                perms, "descriptor must be owner-only (0600)");
    }

    @Test
    void publishOmitsTokenWhenNone(@TempDir Path dir) throws IOException {
        var e = LocalEndpoint.create("bioimage-http", null, false, dir);
        Map<String, Object> desc = JsonUtil.parseObject(Files.readString(e.publish(8722)));
        assertEquals(8722, ((Number) desc.get("port")).intValue());
        assertFalse(desc.containsKey("token"), "no token field when none is required");
    }

    @Test
    void instanceYieldsDistinctDescriptor(@TempDir Path dir) {
        var def = LocalEndpoint.create("bioimage-grpc", null, true, dir);
        var lab = LocalEndpoint.create("bioimage-grpc", "lab2", true, dir);
        assertNotEquals(def.descriptorFile(), lab.descriptorFile());
        assertTrue(lab.descriptorFile().getFileName().toString().contains("lab2"));
    }

    @Test
    void cleanupIsIdempotent(@TempDir Path dir) throws IOException {
        var e = LocalEndpoint.create("bioimage-grpc", null, true, dir);
        Path file = e.publish(1234);
        assertTrue(Files.exists(file));
        e.cleanup();
        assertFalse(Files.exists(file));
        e.cleanup();  // must not throw on a missing file
    }
}
