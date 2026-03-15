package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathAccessControlTest {

    @TempDir
    Path tempDir;

    private Path createFile(String relativePath) throws IOException {
        var file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
        return file;
    }

    private Path createDir(String relativePath) throws IOException {
        var dir = tempDir.resolve(relativePath);
        Files.createDirectories(dir);
        return dir;
    }

    // --- Basic allow/deny ---

    @Test
    void allowedByAllowList() throws IOException {
        var file = createFile("data/image.tif");
        var pac = new PathAccessControl(
                List.of(),
                List.of(tempDir.resolve("data")),
                List.of());

        var result = pac.check(file.toString());
        assertInstanceOf(AccessResult.Allowed.class, result);
    }

    @Test
    void allowedByClientRoot() throws IOException {
        var file = createFile("project/sample.nd2");
        var pac = new PathAccessControl(
                List.of(),
                List.of(),
                List.of(tempDir.resolve("project")));

        var result = pac.check(file.toString());
        assertInstanceOf(AccessResult.Allowed.class, result);
    }

    @Test
    void deniedWhenNotUnderAnyGrant() throws IOException {
        var allowed = createDir("allowed");
        var file = createFile("elsewhere/image.tif");
        var pac = new PathAccessControl(
                List.of(),
                List.of(allowed),
                List.of());

        var result = pac.check(file.toString());
        assertInstanceOf(AccessResult.Denied.class, result);
    }

    // --- Deny list precedence ---

    @Test
    void denyListOverridesAllowList() throws IOException {
        var file = createFile("data/private/secret.tif");
        var pac = new PathAccessControl(
                List.of(tempDir.resolve("data/private")),
                List.of(tempDir.resolve("data")),
                List.of());

        var result = pac.check(file.toString());
        assertInstanceOf(AccessResult.Denied.class, result);
    }

    @Test
    void denyListOverridesClientRoot() throws IOException {
        var file = createFile("project/restricted/image.tif");
        var pac = new PathAccessControl(
                List.of(tempDir.resolve("project/restricted")),
                List.of(),
                List.of(tempDir.resolve("project")));

        var result = pac.check(file.toString());
        assertInstanceOf(AccessResult.Denied.class, result);
    }

    // --- Symlink traversal ---

    @Test
    void symlinkIntoDeniedDirectoryIsDenied() throws IOException {
        var secretFile = createFile("denied/secret.tif");
        var allowedDir = createDir("allowed");
        var symlink = allowedDir.resolve("sneaky.tif");
        Files.createSymbolicLink(symlink, secretFile);

        var pac = new PathAccessControl(
                List.of(tempDir.resolve("denied")),
                List.of(allowedDir),
                List.of());

        var result = pac.check(symlink.toString());
        assertInstanceOf(AccessResult.Denied.class, result);
    }

    @Test
    void symlinkFromDeniedIntoAllowedIsAllowed() throws IOException {
        var realFile = createFile("allowed/image.tif");
        var deniedDir = createDir("denied");
        var symlink = deniedDir.resolve("link.tif");
        Files.createSymbolicLink(symlink, realFile);

        var pac = new PathAccessControl(
                List.of(deniedDir),
                List.of(tempDir.resolve("allowed")),
                List.of());

        // The symlink resolves to the allowed directory, so access is granted.
        var result = pac.check(symlink.toString());
        assertInstanceOf(AccessResult.Allowed.class, result);
    }

    // --- Dot-dot traversal ---

    @Test
    void dotDotCannotEscapeAllowedRoot() throws IOException {
        var file = createFile("outside/image.tif");
        var allowedDir = createDir("allowed");
        var pac = new PathAccessControl(
                List.of(),
                List.of(allowedDir),
                List.of());

        // Attempt to use .. to escape
        var sneakyPath = allowedDir + "/../outside/image.tif";
        var result = pac.check(sneakyPath);
        assertInstanceOf(AccessResult.Denied.class, result);
    }

    // --- Edge cases ---

    @Test
    void rootDirectoryItselfIsAllowed() throws IOException {
        var dir = createDir("data");
        var pac = new PathAccessControl(
                List.of(),
                List.of(dir),
                List.of());

        var result = pac.check(dir.toString());
        assertInstanceOf(AccessResult.Allowed.class, result);
    }

    @Test
    void nonexistentPathIsDenied() throws IOException {
        var pac = new PathAccessControl(
                List.of(),
                List.of(tempDir),
                List.of());

        var result = pac.check(tempDir.resolve("no/such/file.tif").toString());
        assertInstanceOf(AccessResult.Denied.class, result);
        var denied = (AccessResult.Denied) result;
        assertTrue(denied.reason().contains("Cannot resolve path"));
    }

    @Test
    void invalidPathSyntaxIsDenied() throws IOException {
        var pac = new PathAccessControl(
                List.of(),
                List.of(tempDir),
                List.of());

        var result = pac.check("\0invalid");
        assertInstanceOf(AccessResult.Denied.class, result);
    }

    @Test
    void relativeConfigPathIsRejected() {
        assertThrows(IOException.class, () ->
                new PathAccessControl(
                        List.of(),
                        List.of(Path.of("relative/path")),
                        List.of()));
    }

    @Test
    void emptyListsMeanNothingIsAccessible() throws IOException {
        var file = createFile("image.tif");
        var pac = new PathAccessControl(List.of(), List.of(), List.of());

        var result = pac.check(file.toString());
        assertInstanceOf(AccessResult.Denied.class, result);
    }
}
