package lab.kerrr.mcpbio.bioimageserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * Determines whether the server is permitted to access a given filesystem path.
 *
 * <p>Access is governed by three ordered sets of rules:
 * <ol>
 *   <li><b>Deny list</b> — paths the server must never access, regardless of
 *       any other grant.  Takes absolute precedence.</li>
 *   <li><b>Allow list</b> — paths explicitly permitted by the server
 *       configuration (e.g. set by the user in the JBang runner).</li>
 *   <li><b>Client roots</b> — paths declared by the MCP client as in-scope
 *       for the session.</li>
 * </ol>
 *
 * <p>A path is accessible if and only if it is not under any deny-list entry
 * <b>and</b> it is under at least one allow-list entry or client root.
 *
 * <p>All paths are canonicalized before comparison: symlinks are resolved and
 * {@code ..} components are eliminated, so they cannot be used to escape
 * the declared boundaries.
 */
public class PathAccessControl {

    private final List<Path> denyList;
    private final List<Path> allowList;
    private final List<Path> clientRoots;

    /**
     * Constructs a PathAccessControl with the given path lists.
     * All supplied paths are canonicalized eagerly so that later checks
     * are fast and consistent.
     *
     * @throws IOException if any configured path cannot be canonicalized
     *         (e.g. a deny-list entry points to a nonexistent location
     *         that cannot be resolved)
     */
    public PathAccessControl(
            List<Path> denyList,
            List<Path> allowList,
            List<Path> clientRoots) throws IOException {
        this.denyList = canonicalizeAll(denyList, "deny list");
        this.allowList = canonicalizeAll(allowList, "allow list");
        this.clientRoots = canonicalizeAll(clientRoots, "client roots");
    }

    /**
     * The result of checking whether a path is accessible.
     */
    public sealed interface AccessResult {
        /** The path is accessible; {@code canonicalPath} is the resolved real path. */
        record Allowed(Path canonicalPath) implements AccessResult {}
        /** The path is not accessible; {@code reason} explains why. */
        record Denied(String reason) implements AccessResult {}
    }

    /**
     * Checks whether the given path is accessible under the current rules.
     *
     * @param rawPath the path to check, as provided by the caller
     * @return an {@link AccessResult} indicating whether access is permitted
     */
    public AccessResult check(String rawPath) {
        Path parsed;
        try {
            parsed = Path.of(rawPath);
        } catch (InvalidPathException e) {
            return new AccessResult.Denied("Invalid path syntax: " + e.getMessage());
        }

        Path canonical;
        try {
            canonical = parsed.toRealPath();
        } catch (IOException e) {
            return new AccessResult.Denied(
                    "Cannot resolve path (does it exist?): " + e.getMessage());
        }

        // Rule 1: deny list takes absolute precedence.
        for (var denied : denyList) {
            if (isUnderOrEqual(canonical, denied)) {
                return new AccessResult.Denied(
                        "Path is under denied directory: " + denied);
            }
        }

        // Rule 2: check allow list (server config).
        for (var allowed : allowList) {
            if (isUnderOrEqual(canonical, allowed)) {
                return new AccessResult.Allowed(canonical);
            }
        }

        // Rule 3: check client roots.
        for (var root : clientRoots) {
            if (isUnderOrEqual(canonical, root)) {
                return new AccessResult.Allowed(canonical);
            }
        }

        // No grant matched.
        return new AccessResult.Denied(
                "Path is not under any allowed directory or client root");
    }

    /** Returns true if {@code path} is equal to or a descendant of {@code ancestor}. */
    private static boolean isUnderOrEqual(Path path, Path ancestor) {
        return path.startsWith(ancestor);
    }

    private static List<Path> canonicalizeAll(List<Path> paths, String listName)
            throws IOException {
        var builder = new java.util.ArrayList<Path>(paths.size());
        for (var p : paths) {
            if (!p.isAbsolute()) {
                throw new IOException(
                        listName + ": paths must be absolute, got: " + p);
            }
            // Use toRealPath when the path exists (resolves symlinks).
            // Fall back to normalize().toAbsolutePath() for paths that
            // don't exist yet (e.g. a deny rule for a directory that
            // may be created later).
            if (Files.exists(p)) {
                builder.add(p.toRealPath());
            } else {
                builder.add(p.normalize().toAbsolutePath());
            }
        }
        return List.copyOf(builder);
    }
}
