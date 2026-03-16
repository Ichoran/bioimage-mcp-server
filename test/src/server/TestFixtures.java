package lab.kerrr.mcpbio.bioimageserver;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Manages downloaded test fixture files for integration tests.
 *
 * <p>Files are cached in {@code test/fixtures/} under the project root,
 * which is located by walking up from the current working directory
 * until we find {@code build.mill.yaml}.  This survives Mill's test
 * sandboxing (which sets the CWD to a directory under {@code out/}).
 *
 * <p>If a fixture is not present, it is downloaded from its URL on
 * first access.  If the download fails (no network, server down) or
 * the project root cannot be found, the caller gets {@code null} and
 * should use {@code Assumptions.assumeTrue} to skip the test rather
 * than fail.
 */
final class TestFixtures {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(120);

    /** Lazily resolved; null means "couldn't find it." */
    private static final Path FIXTURES_DIR = findFixturesDir();

    /** A CZI file from the IDR (Image Data Resource) collection. */
    static final FixtureDef CZI_IDR0011 = new FixtureDef(
            "Plate1-Blue-A-02-Scene-1-P2-E1-01.czi",
            "https://downloads.openmicroscopy.org/images/Zeiss-CZI/idr0011/"
                    + "Plate1-Blue-A_TS-Stinger/"
                    + "Plate1-Blue-A-02-Scene-1-P2-E1-01.czi");

    record FixtureDef(String filename, String url) {}

    /**
     * Walk up from the CWD looking for {@code build.mill.yaml}.  When
     * found, verify that {@code test/} exists at the same level, and
     * return {@code test/fixtures/} under it.  Returns {@code null}
     * if we hit the filesystem root without finding the marker.
     */
    private static Path findFixturesDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("build.mill.yaml"))) {
                Path testDir = dir.resolve("test");
                if (Files.isDirectory(testDir)) {
                    return testDir.resolve("fixtures");
                }
                // Found the build file but no test/ — bail
                return null;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Returns the local path to the fixture file, downloading it if
     * necessary.  Returns {@code null} if the file cannot be obtained
     * (project root not found, no network, server error, etc.).
     */
    static Path resolve(FixtureDef fixture) {
        if (FIXTURES_DIR == null) {
            System.out.println("Cannot locate project root — "
                    + "test fixture download skipped");
            return null;
        }

        Path local = FIXTURES_DIR.resolve(fixture.filename());
        if (Files.exists(local)) {
            return local;
        }

        try {
            Files.createDirectories(FIXTURES_DIR);
        } catch (IOException e) {
            return null;
        }

        System.out.println("Downloading test fixture: " + fixture.filename()
                + " from " + fixture.url());

        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(fixture.url()))
                    .timeout(DOWNLOAD_TIMEOUT)
                    .GET()
                    .build();

            var response = client.send(request,
                    HttpResponse.BodyHandlers.ofFile(local));

            if (response.statusCode() != 200) {
                Files.deleteIfExists(local);
                System.out.println("Download failed: HTTP " + response.statusCode());
                return null;
            }

            System.out.println("Downloaded " + Files.size(local) + " bytes to "
                    + local);
            return local;
        } catch (IOException | InterruptedException e) {
            try { Files.deleteIfExists(local); } catch (IOException ignored) {}
            System.out.println("Download failed: " + e.getMessage());
            return null;
        }
    }

    private TestFixtures() {}
}
