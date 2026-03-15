package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.PathAccessControl.AccessResult;

/**
 * Checks whether a file path is permitted under the current access policy.
 *
 * <p>In production, this wraps {@link PathAccessControl#check}.  In tests,
 * a simple lambda (e.g. {@code path -> new AccessResult.Allowed(Path.of(path))})
 * can be used.
 */
@FunctionalInterface
public interface PathValidator {

    /**
     * Check whether the given raw path is accessible.
     *
     * @param rawPath the path as provided by the caller (may be relative,
     *                contain symlinks, etc.)
     * @return an {@link AccessResult} indicating whether access is permitted
     */
    AccessResult check(String rawPath);

    /** A validator that allows all paths (for testing only). */
    static PathValidator allowAll() {
        return rawPath -> new AccessResult.Allowed(java.nio.file.Path.of(rawPath));
    }
}
