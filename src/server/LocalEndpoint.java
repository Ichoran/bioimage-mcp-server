package lab.kerrr.mcpbio.bioimageserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-user identity + discovery for the network transports.
 *
 * <p>Security here is two-sided, and one helper covers both halves:
 * <ul>
 *   <li><b>Keep others out.</b>  A {@code LocalEndpoint} carries a ~256-bit
 *       random {@code token} (when requested) that a server checks in
 *       constant time on every call.  The token is the real gate for the
 *       network transports (gRPC always; HTTP opt-in) and is
 *       platform-independent.</li>
 *   <li><b>Let the right user in.</b>  After the server binds, {@link #publish}
 *       writes a small JSON <em>descriptor</em> file — {@code {"port",
 *       "token"}} — into a <em>per-user</em> runtime directory.  A same-user
 *       client reads that one file to learn <em>where</em> to connect
 *       <em>and</em> the secret to present.  Because each user has their own
 *       runtime directory, two users running their own instances never collide
 *       and never see each other's descriptor.</li>
 * </ul>
 *
 * <p><b>Where the files live (per-user by construction):</b>
 * <ul>
 *   <li><b>Linux:</b> {@code $XDG_RUNTIME_DIR} ({@code /run/user/<uid>}, mode
 *       0700).</li>
 *   <li><b>macOS:</b> no {@code XDG_RUNTIME_DIR}; {@code java.io.tmpdir} is
 *       {@code $TMPDIR} ({@code /var/folders/…/T/}), already a per-user 0700
 *       directory.</li>
 *   <li><b>Windows:</b> {@code java.io.tmpdir} is under the user profile
 *       ({@code %LOCALAPPDATA%\Temp}), restricted to the user by inherited
 *       ACLs.</li>
 * </ul>
 * On a non-XDG platform a {@code bioimage-<user>/} subdirectory is used so the
 * files are never dropped bare into a shared {@code /tmp}.
 *
 * <p><b>File permissions are branched, not assumed.</b>  Where the filesystem
 * supports POSIX permissions (Linux, macOS) the runtime directory is created
 * 0700 and each descriptor 0600 — and a pre-existing directory is rejected
 * unless it is owned by us and not group/world-accessible (anti-squat).  Where
 * POSIX is absent (Windows, NTFS) we rely on the user-profile directory's
 * inherited ACL; we do <em>not</em> pretend with {@code File.setReadable},
 * which is effectively a no-op there.  The token — not the file mode — is what
 * actually keeps other users off the socket/port, so functionality is
 * identical across platforms; only the depth of the file-layer defense varies.
 */
final class LocalEndpoint {

    /** Whether this filesystem honors POSIX permissions (Linux, macOS). */
    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private static final Set<PosixFilePermission> DIR_0700 =
            EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);

    private static final Set<PosixFilePermission> FILE_0600 =
            EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);

    /** The auth token, or null when this endpoint requires no token. */
    private final String token;

    /** The descriptor file path (written by {@link #publish}). */
    private final Path descriptorFile;

    private LocalEndpoint(String token, Path descriptorFile) {
        this.token = token;
        this.descriptorFile = descriptorFile;
    }

    // ================================================================
    // Construction
    // ================================================================

    /**
     * Create an endpoint for {@code serviceName} (e.g. {@code "bioimage-grpc"}),
     * optionally generating an auth token.  Resolves and ensures the per-user
     * runtime directory but does <em>not</em> write the descriptor yet — the
     * port is unknown until the server binds, so the caller invokes
     * {@link #publish} afterward.
     *
     * @param serviceName base name for the descriptor file
     * @param instance    optional instance label to disambiguate several
     *                    same-user instances (null/blank for the default)
     * @param withToken   whether to generate and require an auth token
     */
    static LocalEndpoint create(String serviceName, String instance, boolean withToken)
            throws IOException {
        return create(serviceName, instance, withToken, ensureUserRuntimeDir());
    }

    /** Test seam: place the descriptor in an explicit (already-private) directory. */
    static LocalEndpoint create(String serviceName, String instance, boolean withToken,
                                Path dir) {
        String token = withToken ? generateToken() : null;
        String name = serviceName
                + (instance == null || instance.isBlank() ? "" : "-" + sanitize(instance))
                + ".json";
        return new LocalEndpoint(token, dir.resolve(name));
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];               // 256 bits
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ================================================================
    // Accessors / verification
    // ================================================================

    /** The auth token, or null if this endpoint requires none. */
    String token() {
        return token;
    }

    /** The descriptor file path (valid whether or not {@link #publish} ran). */
    Path descriptorFile() {
        return descriptorFile;
    }

    /**
     * Constant-time check of a presented credential against the token.  A
     * leading {@code "Bearer "} is stripped.  Returns false on null/mismatch;
     * returns true only when this endpoint has a token and it matches.
     */
    boolean verify(String presented) {
        if (token == null || presented == null) {
            return false;
        }
        String p = presented.startsWith("Bearer ")
                ? presented.substring("Bearer ".length())
                : presented;
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                p.getBytes(StandardCharsets.UTF_8));
    }

    // ================================================================
    // Descriptor lifecycle
    // ================================================================

    /**
     * Write the discovery descriptor ({@code {"port", "token"}}) once the
     * actual bound port is known.  The file is created owner-only (0600) on
     * POSIX filesystems; on others it inherits the per-user directory's ACL.
     *
     * @return the descriptor path (for startup logging)
     */
    Path publish(int boundPort) throws IOException {
        var desc = new LinkedHashMap<String, Object>();
        desc.put("port", boundPort);
        if (token != null) {
            desc.put("token", token);
        }
        byte[] data = JsonUtil.toJson(desc).getBytes(StandardCharsets.UTF_8);

        // Recreate so the file is owner-only from the first byte (no 0644 window).
        Files.deleteIfExists(descriptorFile);
        if (POSIX) {
            Files.createFile(descriptorFile, asAttr(FILE_0600));
        } else {
            Files.createFile(descriptorFile);
        }
        Files.write(descriptorFile, data);
        return descriptorFile;
    }

    /** Remove the descriptor file; idempotent and exception-safe (used on shutdown). */
    void cleanup() {
        try {
            Files.deleteIfExists(descriptorFile);
        } catch (IOException ignored) {
            // best-effort during shutdown
        }
    }

    // ================================================================
    // Per-user runtime directory (shared with the socket transport)
    // ================================================================

    /**
     * Resolve the per-user runtime directory <em>without</em> touching the
     * filesystem — safe to call from CLI option descriptions.  Returns
     * {@code $XDG_RUNTIME_DIR} when set, else a {@code bioimage-<user>/}
     * subdirectory under {@code java.io.tmpdir}.
     */
    static Path resolveRuntimeDir() {
        String xdg = System.getenv("XDG_RUNTIME_DIR");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg);
        }
        String tmp = System.getProperty("java.io.tmpdir", "/tmp");
        String user = sanitize(System.getProperty("user.name", "user"));
        return Path.of(tmp, "bioimage-" + user);
    }

    /** Resolve and create the per-user runtime directory, verifying it is private. */
    static Path ensureUserRuntimeDir() throws IOException {
        Path dir = resolveRuntimeDir();
        ensurePrivateDir(dir);
        return dir;
    }

    /**
     * Ensure {@code dir} exists and is private to the current user.  Creates it
     * 0700 when absent (POSIX); when it already exists, refuses (on POSIX) any
     * directory not owned by us or readable by group/others — guarding against
     * another user squatting a predictable path in a shared {@code /tmp}.  On
     * non-POSIX filesystems we trust the user-profile directory's ACL.
     */
    static void ensurePrivateDir(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            if (POSIX) {
                PosixFileAttributes attrs =
                        Files.readAttributes(dir, PosixFileAttributes.class);
                String me = System.getProperty("user.name");
                if (me != null && !me.equals(attrs.owner().getName())) {
                    throw new IOException("refusing to use runtime directory owned by "
                            + attrs.owner().getName() + " (expected " + me + "): " + dir);
                }
                if (attrs.permissions().stream().anyMatch(LocalEndpoint::isGroupOrOther)) {
                    throw new IOException(
                            "refusing to use group/world-accessible runtime directory: " + dir);
                }
            }
            return;
        }
        if (POSIX) {
            Files.createDirectories(dir, asAttr(DIR_0700));
        } else {
            Files.createDirectories(dir);
        }
    }

    private static boolean isGroupOrOther(PosixFilePermission p) {
        return switch (p) {
            case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
                 OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
            default -> false;
        };
    }

    /**
     * Best-effort tighten an existing file/socket to owner-only (0600) on POSIX
     * filesystems; a no-op (with a warning) where POSIX is unsupported.  Used by
     * the socket transport on its bound socket file.
     */
    static void restrictToOwner(Path path) {
        if (!POSIX) {
            return;  // Windows: rely on the per-user directory ACL
        }
        try {
            Files.setPosixFilePermissions(path, FILE_0600);
        } catch (IOException | UnsupportedOperationException e) {
            System.err.println("WARN: could not restrict permissions on " + path
                    + ": " + e.getMessage());
        }
    }

    private static FileAttribute<Set<PosixFilePermission>> asAttr(Set<PosixFilePermission> perms) {
        return PosixFilePermissions.asFileAttribute(perms);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
