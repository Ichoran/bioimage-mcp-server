package lab.kerrr.mcpbio.bioimageserver;

import java.io.IOException;
import java.nio.file.Path;

/**
 * An {@link ImageReader} view over a {@link ImageSession}'s already-open reader.
 *
 * <p>The stateless tools all follow the pattern
 * {@code try (var r = factory.get()) { r.open(path); ... }}.  This wrapper lets
 * those tools run <em>unchanged</em> against a reader a session opened once and
 * keeps open, by neutralizing the two lifecycle calls:
 * <ul>
 *   <li>{@link #open(Path)} is a no-op — the delegate is already open, and the
 *       session validated and canonicalized the path when it opened it.</li>
 *   <li>{@link #close()} is a no-op — the session owns the delegate and closes
 *       it exactly once, so a per-operation try-with-resources must not.</li>
 * </ul>
 * Every data-bearing method delegates straight through.  This class is
 * constructed only inside the session path of {@link BioImageService}, and the
 * caller holds the session lock for the duration of the operation (the delegate
 * is not thread-safe).
 */
final class HeldImageReader implements ImageReader {

    private final ImageReader delegate;

    HeldImageReader(ImageReader delegate) {
        this.delegate = delegate;
    }

    /** No-op: the delegate was already opened (and its path validated) by the session. */
    @Override
    public void open(Path path) {
        // intentionally empty
    }

    /** No-op: the session owns the delegate's lifecycle and closes it exactly once. */
    @Override
    public void close() {
        // intentionally empty
    }

    @Override
    public int getSeriesCount() {
        return delegate.getSeriesCount();
    }

    @Override
    public ImageMetadata getMetadata(int series, ImageMetadata.DetailLevel detailLevel) {
        return delegate.getMetadata(series, detailLevel);
    }

    @Override
    public boolean isLittleEndian(int series) {
        return delegate.isLittleEndian(series);
    }

    @Override
    public byte[] readPlane(int series, int channel, int z, int timepoint)
            throws IOException {
        return delegate.readPlane(series, channel, z, timepoint);
    }

    @Override
    public String getOMEXML() {
        return delegate.getOMEXML();
    }

    @Override
    public int getOriginalMetadataCount() {
        return delegate.getOriginalMetadataCount();
    }
}
