package lab.kerrr.mcpbio.bioimageserver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A stateful, kept-open reader session.
 *
 * <p>On the persistent-connection transports (UDS socket, gRPC) a client can
 * {@code open} an image once and address subsequent read operations by the
 * returned {@code handle}, reusing one already-open Bio-Formats reader instead
 * of re-opening and re-parsing the file on every call.
 *
 * <p>The session owns the reader's lifecycle: the reader is opened once when
 * the session is created and closed exactly once by {@link #close()} — driven
 * by the owning transport when the client sends an explicit close or the
 * connection drops.
 *
 * <p>Bio-Formats readers are not thread-safe, so every operation against
 * {@link #reader()} must be performed while holding {@link #lock()}.  Closing
 * also takes the lock, so the reader is never closed while an operation is
 * mid-read.
 */
final class ImageSession implements AutoCloseable {

    private final String handle;
    private final Path canonicalPath;
    private final ImageReader reader;
    private final ReentrantLock lock = new ReentrantLock();

    ImageSession(String handle, Path canonicalPath, ImageReader reader) {
        this.handle = handle;
        this.canonicalPath = canonicalPath;
        this.reader = reader;
    }

    String handle() {
        return handle;
    }

    /** The validated, canonical source path the reader was opened on. */
    Path canonicalPath() {
        return canonicalPath;
    }

    /** The open reader.  Operations must hold {@link #lock()} while using it. */
    ImageReader reader() {
        return reader;
    }

    /** Serializes operations on (and the closing of) the non-thread-safe reader. */
    ReentrantLock lock() {
        return lock;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
