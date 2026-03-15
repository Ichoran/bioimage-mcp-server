package lab.kerrr.mcpbio.bioimageserver;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Abstraction over microscopy image file readers.
 *
 * <p>This is the boundary between tool logic and format-specific code.
 * Tool implementations depend only on this interface and the model
 * records in this package — never on Bio-Formats types directly.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Create the reader instance.
 *   <li>Call {@link #open(Path)} to read and parse the file.
 *   <li>Query metadata and read planes as needed.
 *   <li>Call {@link #close()} (or use try-with-resources).
 * </ol>
 *
 * <p>Implementations are not required to be thread-safe.  For the
 * stdio MCP server (single client, sequential requests), this is
 * not an issue.  For future concurrent scenarios, callers should
 * use per-request reader instances.
 *
 * <h3>Pixel data byte order</h3>
 * Raw pixel bytes returned by {@link #readPlane} use the byte order
 * indicated by {@link #isLittleEndian}.  Callers must check this to
 * correctly interpret multi-byte pixel types (int16, float32, etc.).
 */
public interface ImageReader extends AutoCloseable {

    /**
     * Open an image file for reading.
     *
     * <p>This reads and parses the file's structure and metadata.
     * After this call returns, metadata queries are answered from
     * in-memory structures and do not perform further I/O.
     *
     * @param path absolute path to the image file
     * @throws IOException if the file cannot be read or is not a
     *         recognized image format
     */
    void open(Path path) throws IOException;

    /**
     * Returns the number of image series in the open file.
     *
     * <p>Multi-series formats (LIF, CZI with scenes, etc.) may
     * contain many series; single-image formats always return 1.
     *
     * @throws IllegalStateException if no file is open
     */
    int getSeriesCount();

    /**
     * Returns metadata for the open file, with detailed information
     * for the specified series.
     *
     * <p>The returned {@link ImageMetadata#allSeries()} always contains
     * summary info for every series in the file, regardless of which
     * series is selected for detailed inspection.
     *
     * @param series      zero-based series index
     * @param detailLevel how much metadata to return
     * @throws IllegalArgumentException if series is out of range
     * @throws IllegalStateException    if no file is open
     */
    ImageMetadata getMetadata(int series, ImageMetadata.DetailLevel detailLevel);

    /**
     * Returns whether pixel data for the given series is stored in
     * little-endian byte order.
     *
     * <p>Callers must use this when interpreting the raw bytes
     * returned by {@link #readPlane} for multi-byte pixel types.
     *
     * @param series zero-based series index
     * @throws IllegalArgumentException if series is out of range
     * @throws IllegalStateException    if no file is open
     */
    boolean isLittleEndian(int series);

    /**
     * Read a single 2D plane of raw pixel data.
     *
     * <p>The returned array contains {@code sizeX * sizeY *
     * pixelType.bytesPerPixel()} bytes in the byte order indicated
     * by {@link #isLittleEndian}.  Pixels are stored in row-major
     * order (X varies fastest).
     *
     * @param series    zero-based series index
     * @param channel   zero-based channel index
     * @param z         zero-based Z-slice index
     * @param timepoint zero-based timepoint index
     * @return raw pixel bytes for the requested plane
     * @throws IllegalArgumentException if any index is out of range
     * @throws IllegalStateException    if no file is open
     * @throws IOException              if pixel data cannot be read
     */
    byte[] readPlane(int series, int channel, int z, int timepoint)
            throws IOException;

    /**
     * Returns the OME-XML metadata for the open file, or {@code null}
     * if the reader does not provide OME-XML.
     *
     * <p>When available, this is the complete OME-XML document that
     * Bio-Formats generates from the source format.  Export tools can
     * pass this XML through to the output file, surgically modifying
     * only the elements affected by subsetting, so that metadata the
     * tool doesn't understand survives the round-trip.
     *
     * <p>The XML may include {@code OriginalMetadataAnnotation}
     * elements containing format-specific key-value pairs that
     * Bio-Formats extracted but could not map to OME schema elements.
     *
     * @return OME-XML string, or {@code null} if not available
     * @throws IllegalStateException if no file is open
     */
    default String getOMEXML() {
        return null;
    }

    /**
     * Returns the number of flat (key-value) metadata entries the
     * reader extracted from the source format.
     *
     * <p>These are format-specific entries (e.g. scanner voltages,
     * acquisition parameters) that the reader found in the file.
     * Some may have been mapped to structured OME-XML elements, and
     * some may appear as {@code OriginalMetadataAnnotation} entries
     * in the OME-XML (see {@link #getOMEXML()}), but others may
     * not be serialized at all.
     *
     * <p>Export tools can compare this count against the number of
     * {@code OriginalMetadataAnnotation} entries in the OME-XML to
     * detect metadata that will not survive format conversion.
     *
     * @return count of flat metadata entries, or 0 if unknown
     */
    default int getOriginalMetadataCount() {
        return 0;
    }

    /**
     * Close the reader and release all resources.
     *
     * <p>After closing, no other methods may be called.  Calling
     * close on an already-closed or never-opened reader is a no-op.
     */
    @Override
    void close() throws IOException;
}
