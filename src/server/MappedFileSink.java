package lab.kerrr.mcpbio.bioimageserver;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * {@link PixelSink} backed by a client-owned file the client maps into its
 * own address space (e.g. a {@code /dev/shm} or {@code tmpfs} file).
 *
 * <p>The server writes plane bytes into the file via positional
 * {@link FileChannel#write(ByteBuffer, long)} calls.  When the file lives on
 * {@code tmpfs} its pages are RAM, so the client's {@code mmap} view reflects
 * the writes without a disk round-trip — this is the "shared memory" path
 * with no FFM and no 2&nbsp;GB {@link java.nio.MappedByteBuffer} limit
 * (positional writes take {@code long} offsets).
 *
 * <p>The client is responsible for creating and sizing the file before the
 * deposit and for unlinking it afterward.  This sink only verifies the file
 * is already large enough for the requested write; it never grows, shrinks,
 * or deletes it.
 */
final class MappedFileSink implements PixelSink {

    private final RandomAccessFile raf;
    private final FileChannel channel;

    /**
     * Open {@code path} for writing and verify it can hold {@code requiredBytes}.
     *
     * @throws IOException if the file cannot be opened, or is smaller than
     *         {@code requiredBytes} (the client must allocate the region first
     *         — the server never grows it)
     */
    MappedFileSink(Path path, long requiredBytes) throws IOException {
        this.raf = new RandomAccessFile(path.toFile(), "rw");
        this.channel = raf.getChannel();
        long actual = channel.size();
        if (actual < requiredBytes) {
            try { close(); } catch (IOException ignored) {
                // The open failed validation; the close error is secondary
                // and must not mask the real problem reported below.
            }
            throw new IOException("target region is " + actual + " bytes but "
                    + requiredBytes + " are required; the client must allocate "
                    + "the region to at least the required size before deposit");
        }
    }

    @Override
    public void writeAt(long offset, byte[] data) throws IOException {
        var buf = ByteBuffer.wrap(data);
        long pos = offset;
        while (buf.hasRemaining()) {
            int n = channel.write(buf, pos);
            if (n <= 0) {
                throw new IOException("short write to target region at offset "
                        + pos + " (" + buf.remaining() + " bytes unwritten)");
            }
            pos += n;
        }
    }

    @Override
    public void finish() throws IOException {
        // Flush data (not metadata) so a mapping client sees the bytes.
        channel.force(false);
    }

    @Override
    public void close() throws IOException {
        // Closing the RandomAccessFile closes its channel too.
        raf.close();
    }
}
