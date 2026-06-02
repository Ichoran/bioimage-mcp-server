package lab.kerrr.mcpbio.bioimageserver;

import java.io.IOException;

/**
 * A destination for raw pixel bytes written by {@link BioImageService#deposit}.
 *
 * <p>Abstracts <em>where</em> the deposited plane bytes land so the deposit
 * operation stays transport-neutral.  The v1 implementation
 * ({@link MappedFileSink}) targets a client-owned memory-mapped file
 * (typically on {@code tmpfs}/{@code /dev/shm}); future implementations can
 * back a POSIX {@code shm_open} object or a Windows named mapping behind the
 * same interface without changing the deposit logic or the wire protocol.
 *
 * <p><b>Lifecycle.</b>  The deposit writes every plane via {@link #writeAt},
 * calls {@link #finish} once to flush, and always {@link #close}s the sink
 * (success or abort) via try-with-resources.  A sink must never delete or
 * unlink the underlying region — the client owns its lifecycle.
 */
public interface PixelSink extends AutoCloseable {

    /**
     * Write {@code data} into the region starting at {@code offset} bytes.
     * The full array is written; a short write is an error, not a partial
     * success.
     *
     * @throws IOException if the bytes cannot be fully written
     */
    void writeAt(long offset, byte[] data) throws IOException;

    /** Flush all written bytes so the client can observe them. */
    void finish() throws IOException;

    /** Release the sink's handle on the region.  Never unlinks it. */
    @Override
    void close() throws IOException;
}
