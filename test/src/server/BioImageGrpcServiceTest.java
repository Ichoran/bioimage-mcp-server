package lab.kerrr.mcpbio.bioimageserver;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import lab.kerrr.mcpbio.bioimageserver.grpc.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BioImageGrpcService} over a real loopback
 * channel.  Uses {@link FakeImageReader} so results are deterministic, and
 * verifies the session lifecycle end-to-end including that a dropped stream
 * closes the kept-open reader.
 */
@Timeout(30)
class BioImageGrpcServiceTest {

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

    /** A loopback channel carrying the server's per-user auth token. */
    private static ManagedChannel authedChannel(BioImageGrpcService grpc) {
        Metadata md = new Metadata();
        md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                grpc.authTokenForTest());
        return ManagedChannelBuilder
                .forAddress("127.0.0.1", grpc.boundPort())
                .usePlaintext()
                .intercept(MetadataUtils.newAttachHeadersInterceptor(md))
                .build();
    }

    /** Collects server messages off the bidi stream. */
    private static final class Collector implements StreamObserver<ServerMsg> {
        final BlockingQueue<ServerMsg> q = new LinkedBlockingQueue<>();
        volatile Throwable error;
        volatile boolean completed;
        @Override public void onNext(ServerMsg m) { q.add(m); }
        @Override public void onError(Throwable t) { error = t; }
        @Override public void onCompleted() { completed = true; }
        ServerMsg take() throws InterruptedException {
            ServerMsg m = q.poll(10, TimeUnit.SECONDS);
            assertNotNull(m, "timed out waiting for a server message");
            return m;
        }
    }

    @Test
    void fullSessionFlowOverLoopback(@TempDir Path dir) throws Exception {
        var service = serviceFor(dir);
        var grpc = BioImageGrpcService.create(service, 0);
        grpc.start(0);
        ManagedChannel channel = authedChannel(grpc);
        try {
            Path src = dir.resolve("src.fake");
            Files.createFile(src);

            var stub = BioImageGrpc.newStub(channel);
            var collector = new Collector();
            StreamObserver<ClientMsg> client = stub.session(collector);

            // Hello.
            assertEquals(ServerMsg.MsgCase.READY, collector.take().getMsgCase());

            // Open → handle.
            client.onNext(ClientMsg.newBuilder().setId("o1")
                    .setOpen(OpenSession.newBuilder().setPath(src.toString())).build());
            ServerMsg opened = collector.take();
            assertEquals(ServerMsg.MsgCase.SESSION_OPENED, opened.getMsgCase());
            String handle = opened.getSessionOpened().getHandle();
            assertFalse(handle.isEmpty());
            assertEquals(1, service.openSessionCount());

            // Inspect by handle → JSON.
            client.onNext(ClientMsg.newBuilder().setId("i1")
                    .setInspect(InspectRequest.newBuilder().setHandle(handle)).build());
            ServerMsg inspected = collector.take();
            assertEquals(ServerMsg.MsgCase.JSON, inspected.getMsgCase());
            assertTrue(inspected.getJson().getJson().contains("\"sizeX\""));

            // OME metadata by handle → format-tagged document.
            client.onNext(ClientMsg.newBuilder().setId("m1")
                    .setOmeMetadata(OmeMetadataRequest.newBuilder().setHandle(handle)).build());
            ServerMsg meta = collector.take();
            assertEquals(ServerMsg.MsgCase.OME_METADATA, meta.getMsgCase());
            assertEquals("ome_xml", meta.getOmeMetadata().getFormat());
            assertTrue(meta.getOmeMetadata().getContent().contains("<OME"));

            // Deposit by handle into a shared-memory-style region.
            long total = (long) X * Y * Z * C * T;
            Path tgt = dir.resolve("shm.bin");
            try (var raf = new RandomAccessFile(tgt.toFile(), "rw")) { raf.setLength(total); }
            client.onNext(ClientMsg.newBuilder().setId("d1")
                    .setDeposit(DepositRequest.newBuilder()
                            .setHandle(handle)
                            .setChannels(":").setZ(":").setT(":")
                            .setTarget(DepositTarget.newBuilder()
                                    .setKind("file").setPath(tgt.toString())
                                    .setCapacityBytes(total)))
                    .build());
            ServerMsg filled = collector.take();
            assertEquals(ServerMsg.MsgCase.FILLED, filled.getMsgCase());
            assertEquals(total, filled.getFilled().getTotalBytes());
            // Descriptor reports the resolved indices on every axis.
            assertEquals(5, filled.getFilled().getSelectionCount());
            var xSel = filled.getFilled().getSelectionList().stream()
                    .filter(a -> a.getAxis().equals("x")).findFirst().orElseThrow();
            assertEquals(0, xSel.getRanges(0).getStart());
            assertEquals(X, xSel.getRanges(0).getStop());
            byte[] bytes = Files.readAllBytes(tgt);
            assertEquals(total, bytes.length);
            boolean nonZero = false;
            for (byte b : bytes) if (b != 0) { nonZero = true; break; }
            assertTrue(nonZero, "deposited region should contain pixel data");

            // y/x sub-ranges are accepted but IGNORED: the dry-run descriptor
            // reports the full plane (shape.x/y), not the requested sub-range.
            client.onNext(ClientMsg.newBuilder().setId("d2")
                    .setDeposit(DepositRequest.newBuilder()
                            .setHandle(handle).setChannels(":").setZ(":").setT(":")
                            .setY("1:2").setX("1:2").setDryRun(true))
                    .build());
            ServerMsg ignored = collector.take();
            assertEquals(ServerMsg.MsgCase.FILLED, ignored.getMsgCase());
            assertEquals(X, ignored.getFilled().getSizeX(), "x sub-range ignored: full X");
            assertEquals(Y, ignored.getFilled().getSizeY(), "y sub-range ignored: full Y");

            // A non-zero pyramid level is REFUSED, never silently downgraded.
            client.onNext(ClientMsg.newBuilder().setId("d3")
                    .setDeposit(DepositRequest.newBuilder()
                            .setHandle(handle).setChannels(":").setZ(":").setT(":")
                            .setLevel(2).setDryRun(true))
                    .build());
            ServerMsg lvl = collector.take();
            assertEquals(ServerMsg.MsgCase.ERROR, lvl.getMsgCase());
            assertEquals("invalid_argument", lvl.getError().getErrorKind());

            // Close → closed; reader released.
            client.onNext(ClientMsg.newBuilder().setId("c1")
                    .setClose(CloseSession.newBuilder().setHandle(handle)).build());
            ServerMsg closed = collector.take();
            assertEquals(ServerMsg.MsgCase.CLOSED, closed.getMsgCase());
            assertEquals(0, service.openSessionCount());

            client.onCompleted();
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            grpc.stop();
        }
    }

    @Test
    void droppedStreamClosesHeldReader(@TempDir Path dir) throws Exception {
        var service = serviceFor(dir);
        var grpc = BioImageGrpcService.create(service, 0);
        grpc.start(0);
        ManagedChannel channel = authedChannel(grpc);
        try {
            Path src = dir.resolve("src.fake");
            Files.createFile(src);

            var stub = BioImageGrpc.newStub(channel);
            var collector = new Collector();
            StreamObserver<ClientMsg> client = stub.session(collector);
            assertEquals(ServerMsg.MsgCase.READY, collector.take().getMsgCase());

            client.onNext(ClientMsg.newBuilder().setId("o1")
                    .setOpen(OpenSession.newBuilder().setPath(src.toString())).build());
            assertEquals(ServerMsg.MsgCase.SESSION_OPENED, collector.take().getMsgCase());
            assertEquals(1, service.openSessionCount());

            // Abruptly drop the connection — the server must close the reader.
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (service.openSessionCount() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(0, service.openSessionCount(),
                    "dropping the stream must close the kept-open reader");
        } finally {
            grpc.stop();
        }
    }

    @Test
    void missingOrWrongTokenIsRejected(@TempDir Path dir) throws Exception {
        var service = serviceFor(dir);
        var grpc = BioImageGrpcService.create(service, 0);   // token required (default)
        grpc.start(0);
        // A plaintext channel with NO auth metadata.
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("127.0.0.1", grpc.boundPort()).usePlaintext().build();
        try {
            var stub = BioImageGrpc.newStub(channel);
            var collector = new Collector();
            stub.session(collector);

            // The interceptor closes the call before the handler runs: no READY,
            // an onError carrying UNAUTHENTICATED instead.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (collector.error == null && System.nanoTime() < deadline) Thread.sleep(20);
            assertNotNull(collector.error, "a token-less call must be rejected");
            assertEquals(Status.Code.UNAUTHENTICATED,
                    Status.fromThrowable(collector.error).getCode());
            assertTrue(collector.q.isEmpty(), "no server messages should reach an unauth'd client");
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            grpc.stop();
        }
    }

    @Test
    void descriptorPublishesBoundPortAndToken(@TempDir Path dir) throws Exception {
        var service = serviceFor(dir);
        var grpc = BioImageGrpcService.create(service, 0);
        grpc.start(0);
        try {
            Path descriptor = grpc.descriptorFileForTest();
            assertNotNull(descriptor);
            assertTrue(Files.exists(descriptor), "descriptor file must be published");

            Map<String, Object> desc = JsonUtil.parseObject(Files.readString(descriptor));
            assertEquals(grpc.boundPort(), ((Number) desc.get("port")).intValue(),
                    "descriptor port must match the bound (ephemeral) port");
            assertEquals(grpc.authTokenForTest(), desc.get("token"),
                    "descriptor token must match the server token");
        } finally {
            grpc.stop();
            assertFalse(Files.exists(grpc.descriptorFileForTest()),
                    "stop() must remove the descriptor");
        }
    }
}
