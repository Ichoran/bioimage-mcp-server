package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the session surface of {@link BioImageSocketService}
 * over a real Unix-domain socket: the NDJSON {@code open} / {@code inspect} /
 * {@code get_plane} / {@code close} dispatch and disconnect cleanup.  Uses
 * {@link FakeImageReader} for deterministic results.
 */
@Timeout(30)
class SocketSessionTest {

    private static final int X = 4, Y = 3, Z = 2, C = 1, T = 1;

    private static BioImageService serviceFor(Path allowDir) {
        Supplier<ImageReader> fake = () -> FakeImageReader.builder()
                .littleEndian(true)
                .addSeries(FakeImageReader.FakeSeries.simple(X, Y, Z, C, T, PixelType.UINT8))
                .build();
        return BioImageService.builder()
                .allow(allowDir.toString())
                .readerFactory(fake)
                .build();
    }

    /** Start the server on a background thread; returns once it is listening. */
    private static BioImageSocketService startServer(BioImageService svc, Path sock)
            throws Exception {
        var server = BioImageSocketService.create(svc, sock);
        Thread.ofVirtual().start(() -> {
            try { server.serve(sock); } catch (Exception ignored) { /* test teardown */ }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(sock) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(sock), "server socket did not appear");
        return server;
    }

    private static void send(OutputStream out, Map<String, Object> msg) throws Exception {
        out.write((JsonUtil.toJson(msg).replace("\n", " ") + "\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static Map<String, Object> readLine(InputStream in) throws Exception {
        var buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') buf.write(b);
        return JsonUtil.parseObject(buf.toString(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> msg(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static SocketChannel connect(Path sock) throws Exception {
        var ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(sock));
        return ch;
    }

    @Test
    void openInspectPlaneCloseOverSocket(@TempDir Path dir) throws Exception {
        var svc = serviceFor(dir);
        Path sock = dir.resolve("svc.sock");
        startServer(svc, sock);
        Path src = dir.resolve("src.fake");
        Files.createFile(src);

        try (var ch = connect(sock);
             var in = new BufferedInputStream(Channels.newInputStream(ch))) {
            OutputStream out = Channels.newOutputStream(ch);

            assertEquals("ready", readLine(in).get("type"));

            send(out, msg("type", "open", "id", "o1", "path", src.toString()));
            var opened = readLine(in);
            assertEquals("session_opened", opened.get("type"));
            String handle = (String) opened.get("handle");
            assertNotNull(handle);
            assertEquals(1, svc.openSessionCount());

            send(out, msg("type", "inspect", "id", "i1", "handle", handle));
            var inspected = readLine(in);
            assertEquals("inspected", inspected.get("type"));
            assertNotNull(inspected.get("result"));

            send(out, msg("type", "get_plane", "id", "p1", "handle", handle,
                    "channel", "0", "z", "0", "t", "0"));
            var plane = readLine(in);
            assertEquals("plane", plane.get("type"));
            assertNotNull(plane.get("png_base64"));

            send(out, msg("type", "close", "id", "c1", "handle", handle));
            var closed = readLine(in);
            assertEquals("closed", closed.get("type"));
            assertEquals(handle, closed.get("handle"));
            assertEquals(0, svc.openSessionCount());
        }
    }

    @Test
    void disconnectClosesOpenSession(@TempDir Path dir) throws Exception {
        var svc = serviceFor(dir);
        Path sock = dir.resolve("svc.sock");
        startServer(svc, sock);
        Path src = dir.resolve("src.fake");
        Files.createFile(src);

        try (var ch = connect(sock);
             var in = new BufferedInputStream(Channels.newInputStream(ch))) {
            OutputStream out = Channels.newOutputStream(ch);
            assertEquals("ready", readLine(in).get("type"));
            send(out, msg("type", "open", "id", "o1", "path", src.toString()));
            assertEquals("session_opened", readLine(in).get("type"));
            assertEquals(1, svc.openSessionCount());
        }
        // Channel closed: the server should drop the session and close the reader.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (svc.openSessionCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, svc.openSessionCount(),
                "disconnect must close the kept-open reader");
    }
}
