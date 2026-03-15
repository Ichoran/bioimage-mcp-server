package lab.kerrr.mcpbio.bioimageserver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A synthetic {@link ImageWriter} that captures everything written,
 * for test assertions.
 *
 * <p>No actual file I/O is performed.  Tests can inspect the
 * recorded OME-XML, compression setting, series/plane writes, and
 * byte counts after the tool has run.
 */
public final class FakeImageWriter implements ImageWriter {

    private Path outputPath;
    private String omeXml;
    private String compression;
    private int currentSeries;
    private long bytesWritten;
    private boolean open;
    private boolean closed;
    private final List<WrittenPlane> planes = new ArrayList<>();

    /**
     * Record of a single plane write.
     *
     * @param series     the series that was active when the plane was written
     * @param planeIndex the plane index within the series
     * @param data       the raw pixel bytes (defensive copy)
     */
    public record WrittenPlane(int series, int planeIndex, byte[] data) {
        public WrittenPlane {
            data = data.clone();
        }
    }

    @Override
    public void open(Path path, String omeXml, String compression)
            throws IOException {
        this.outputPath = path;
        this.omeXml = omeXml;
        this.compression = compression;
        this.currentSeries = 0;
        this.bytesWritten = 0;
        this.open = true;
        this.closed = false;
    }

    @Override
    public void setSeries(int series) throws IOException {
        checkOpen();
        this.currentSeries = series;
    }

    @Override
    public void writePlane(int planeIndex, byte[] data) throws IOException {
        checkOpen();
        planes.add(new WrittenPlane(currentSeries, planeIndex, data));
        bytesWritten += data.length;
    }

    @Override
    public long getBytesWritten() {
        return bytesWritten;
    }

    @Override
    public void close() throws IOException {
        open = false;
        closed = true;
    }

    // ---- Test accessors ----

    /** The path passed to {@link #open}. */
    public Path outputPath() { return outputPath; }

    /** The OME-XML passed to {@link #open}. */
    public String omeXml() { return omeXml; }

    /** The compression string passed to {@link #open}. */
    public String compression() { return compression; }

    /** All planes written, in order. */
    public List<WrittenPlane> planes() { return List.copyOf(planes); }

    /** Number of planes written. */
    public int planeCount() { return planes.size(); }

    /** Whether {@link #close} was called. */
    public boolean isClosed() { return closed; }

    private void checkOpen() {
        if (!open) {
            throw new IllegalStateException("writer is not open");
        }
    }
}
