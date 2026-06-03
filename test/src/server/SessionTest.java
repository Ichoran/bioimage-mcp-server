package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the stateful session layer in {@link BioImageService}:
 * {@code openSession} / {@code closeSession} and the {@code handle}-routed
 * read operations.  Uses {@link FakeImageReader} (deterministic pixel formula
 * {@code y*sizeX + x + c*7 + z*13 + t*31}) wrapped in a counting/closeable
 * tracker so we can assert the reader is opened once and closed exactly once.
 */
@Timeout(20)
class SessionTest {

    private static final int X = 4, Y = 3, Z = 2, C = 1, T = 1;

    /** Wraps a delegate reader to record close() calls. */
    private static final class TrackingReader implements ImageReader {
        private final ImageReader delegate;
        final AtomicInteger closes;
        TrackingReader(ImageReader delegate, AtomicInteger closes) {
            this.delegate = delegate;
            this.closes = closes;
        }
        @Override public void open(Path path) throws IOException { delegate.open(path); }
        @Override public int getSeriesCount() { return delegate.getSeriesCount(); }
        @Override public ImageMetadata getMetadata(int s, ImageMetadata.DetailLevel d) {
            return delegate.getMetadata(s, d);
        }
        @Override public boolean isLittleEndian(int s) { return delegate.isLittleEndian(s); }
        @Override public byte[] readPlane(int s, int c, int z, int t) throws IOException {
            return delegate.readPlane(s, c, z, t);
        }
        @Override public String getOMEXML() { return delegate.getOMEXML(); }
        @Override public int getOriginalMetadataCount() { return delegate.getOriginalMetadataCount(); }
        @Override public void close() throws IOException { closes.incrementAndGet(); delegate.close(); }
    }

    /** Tracks how many readers the factory created and how many were closed. */
    private record Harness(BioImageService service, AtomicInteger created,
                           AtomicInteger closed) {}

    private static Harness harnessFor(Path allowDir) {
        var created = new AtomicInteger();
        var closed = new AtomicInteger();
        Supplier<ImageReader> factory = () -> {
            created.incrementAndGet();
            var fake = FakeImageReader.builder()
                    .littleEndian(true)
                    .addSeries(FakeImageReader.FakeSeries.simple(X, Y, Z, C, T, PixelType.UINT8))
                    .build();
            return new TrackingReader(fake, closed);
        };
        var service = BioImageService.builder()
                .allow(allowDir.toString())
                .readerFactory(factory)
                .build();
        return new Harness(service, created, closed);
    }

    private static Path touch(Path dir, String name) throws Exception {
        Path p = dir.resolve(name);
        Files.createFile(p);
        return p;
    }

    private static Map<String, Object> args(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String openHandle(BioImageService svc, Path src) {
        var r = svc.openSession(args("path", src.toString()));
        if (r instanceof ToolResult.Success<SessionInfo> s) return s.value().handle();
        var f = (ToolResult.Failure<SessionInfo>) r;
        return fail("openSession failed: " + f.kind() + ": " + f.message());
    }

    @Test
    void openReturnsHandleAndSummary(@TempDir Path dir) throws Exception {
        var h = harnessFor(dir);
        var src = touch(dir, "src.fake");
        var r = h.service().openSession(args("path", src.toString()));
        assertInstanceOf(ToolResult.Success.class, r);
        var info = ((ToolResult.Success<SessionInfo>) r).value();
        assertNotNull(info.handle());
        assertEquals(X, info.summary().detailedSeries().sizeX());
        assertEquals(1, h.service().openSessionCount());
        assertEquals(1, h.created().get());
    }

    @Test
    void handleOpsReuseTheSameOpenReader(@TempDir Path dir) throws Exception {
        var h = harnessFor(dir);
        var src = touch(dir, "src.fake");
        String handle = openHandle(h.service(), src);

        // Several ops by handle: the factory must NOT be invoked again.
        assertInstanceOf(ToolResult.Success.class,
                h.service().inspectImage(args("handle", handle)));
        assertInstanceOf(ToolResult.Success.class,
                h.service().getPlane(args("handle", handle, "channel", "0", "z", "0", "t", "0")));
        assertInstanceOf(ToolResult.Success.class,
                h.service().getIntensityStats(args("handle", handle, "channels", ":", "z", ":", "t", ":")));

        assertEquals(1, h.created().get(), "reader opened exactly once and reused");
        assertEquals(0, h.closed().get(), "reader not closed while session is open");
    }

    @Test
    void depositByHandleWritesExpectedBytes(@TempDir Path dir) throws Exception {
        var h = harnessFor(dir);
        var src = touch(dir, "src.fake");
        String handle = openHandle(h.service(), src);

        long total = (long) X * Y * Z * C * T;
        Path tgt = dir.resolve("shm.bin");
        try (var raf = new java.io.RandomAccessFile(tgt.toFile(), "rw")) { raf.setLength(total); }

        var target = args("kind", "file", "path", tgt.toString(), "capacity_bytes", total);
        var r = h.service().deposit(args("handle", handle,
                "channels", ":", "z", ":", "t", ":", "target", target));
        assertInstanceOf(ToolResult.Success.class, r);

        byte[] bytes = Files.readAllBytes(tgt);
        assertEquals(total, bytes.length);
        for (int z = 0; z < Z; z++) {
            long planeOffset = (long) z * X * Y;   // c=t=0
            for (int y = 0; y < Y; y++) {
                for (int x = 0; x < X; x++) {
                    int exp = (int) ((long) y * X + x + z * 13L) & 0xFF;
                    assertEquals(exp, bytes[(int) (planeOffset + (long) y * X + x)] & 0xFF);
                }
            }
        }
        assertEquals(1, h.created().get(), "deposit reused the session reader");
    }

    @Test
    void closeReleasesReaderAndIsIdempotent(@TempDir Path dir) throws Exception {
        var h = harnessFor(dir);
        var src = touch(dir, "src.fake");
        String handle = openHandle(h.service(), src);

        assertInstanceOf(ToolResult.Success.class, h.service().closeSession(handle));
        assertEquals(1, h.closed().get(), "reader closed exactly once");
        assertEquals(0, h.service().openSessionCount());

        // Idempotent: closing again (or an unknown handle) is still success.
        assertInstanceOf(ToolResult.Success.class, h.service().closeSession(handle));
        assertInstanceOf(ToolResult.Success.class, h.service().closeSession("never-existed"));
        assertEquals(1, h.closed().get(), "no double close");
    }

    @Test
    void omeMetadataByPathAndHandleAndCap(@TempDir Path dir) throws Exception {
        var h = harnessFor(dir);
        var src = touch(dir, "src.fake");

        // By path (stateless).
        var byPath = h.service().getOmeMetadata(args("path", src.toString()));
        var m = assertInstanceOf(ToolResult.Success.class, byPath);
        var doc = (OmeMetadata) ((ToolResult.Success<?>) byPath).value();
        assertEquals("ome_xml", doc.format());
        assertTrue(doc.content().contains("<OME"), "content should be OME-XML");

        // By handle (reuses the open reader).
        String handle = openHandle(h.service(), src);
        var byHandle = h.service().getOmeMetadata(args("handle", handle));
        assertInstanceOf(ToolResult.Success.class, byHandle);
        assertEquals("ome_xml",
                ((OmeMetadata) ((ToolResult.Success<?>) byHandle).value()).format());

        // Cap too small -> INVALID_ARGUMENT, never a truncated document.
        var capped = h.service().getOmeMetadata(
                args("path", src.toString(), "max_response_bytes", 1));
        assertInstanceOf(ToolResult.Failure.class, capped);
        assertEquals(ToolResult.ErrorKind.INVALID_ARGUMENT,
                ((ToolResult.Failure<?>) capped).kind());
    }

    @Test
    void unknownHandleIsInvalidArgument(@TempDir Path dir) throws Exception {
        var h = harnessFor(dir);
        var inspect = h.service().inspectImage(args("handle", "bogus"));
        assertInstanceOf(ToolResult.Failure.class, inspect);
        assertEquals(ToolResult.ErrorKind.INVALID_ARGUMENT,
                ((ToolResult.Failure<?>) inspect).kind());

        var deposit = h.service().deposit(args("handle", "bogus",
                "channels", ":", "z", ":", "t", ":"));
        assertEquals(ToolResult.ErrorKind.INVALID_ARGUMENT,
                ((ToolResult.Failure<?>) deposit).kind());
    }

    @Test
    void openDeniedPathRegistersNothing(@TempDir Path dir) throws Exception {
        var h = harnessFor(dir);
        Path outside = Files.createTempFile("session-outside", ".fake");
        try {
            var r = h.service().openSession(args("path", outside.toString()));
            assertInstanceOf(ToolResult.Failure.class, r);
            assertEquals(ToolResult.ErrorKind.ACCESS_DENIED,
                    ((ToolResult.Failure<?>) r).kind());
            assertEquals(0, h.service().openSessionCount());
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}
