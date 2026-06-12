package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the HTTP transport's opt-in {@code --require-token} auth: operation
 * endpoints demand a matching token (401 otherwise), while {@code /health}
 * stays open for liveness checks.  Binds loopback only.
 */
@Timeout(20)
class HttpTokenTest {

    private static final int X = 4, Y = 3, Z = 1, C = 1, T = 1;

    @Test
    void tokenGatesOperationsButNotHealth(@TempDir Path dir) throws Exception {
        Supplier<ImageReader> fake = () -> FakeImageReader.builder()
                .littleEndian(true)
                .addSeries(FakeImageReader.FakeSeries.simple(X, Y, Z, C, T, PixelType.UINT8))
                .build();
        var svc = BioImageService.builder()
                .allow(dir.toString())
                .readerFactory(fake)
                .build();

        var http = BioImageHttpService.create(svc, 0, "127.0.0.1", true);
        http.start(0);
        try {
            String token = http.authTokenForTest();
            assertNotNull(token, "a token must be generated when --require-token is on");
            Path src = dir.resolve("src.fake");
            Files.createFile(src);

            String base = "http://127.0.0.1:" + http.boundPort();
            String body = "{\"path\":\"" + src + "\"}";
            var client = HttpClient.newHttpClient();

            // No token → 401 with the standard error shape.
            var noTok = client.send(post(base + "/inspect_image", body, null),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, noTok.statusCode());
            assertTrue(noTok.body().contains("access_denied"));

            // Wrong token → 401.
            assertEquals(401, client.send(post(base + "/inspect_image", body, "Bearer nope"),
                    HttpResponse.BodyHandlers.ofString()).statusCode());

            // Correct token via Authorization → 200.
            var ok = client.send(post(base + "/inspect_image", body, "Bearer " + token),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("sizeX"));

            // Correct token via X-Auth-Token header → 200.
            var okHdr = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/inspect_image"))
                            .header("Content-Type", "application/json")
                            .header("X-Auth-Token", token)
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, okHdr.statusCode());

            // Health is open — no token needed.
            var health = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
        } finally {
            http.stop();
        }
    }

    private static HttpRequest post(String url, String body, String authorization) {
        var b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) b.header("Authorization", authorization);
        return b.build();
    }
}
