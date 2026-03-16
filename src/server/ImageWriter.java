package lab.kerrr.mcpbio.bioimageserver;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Abstraction over microscopy image file writers.
 *
 * <p>This is the write-side counterpart to {@link ImageReader}.  Tool
 * implementations depend only on this interface — never on Bio-Formats
 * writer types directly.
 *
 * <p><b>Lifecycle</b></p>
 * <ol>
 *   <li>Create the writer instance.
 *   <li>Call {@link #open(Path, String, String)} with the output path,
 *       OME-XML metadata, and compression type.
 *   <li>For each series, call {@link #setSeries(int)} then write planes
 *       sequentially with {@link #writePlane(int, byte[])}.
 *   <li>Call {@link #close()} to finalize the file (this writes the
 *       OME-XML header in OME-TIFF).
 * </ol>
 *
 * <p>Planes within a series must be written in order (plane index 0, 1,
 * 2, ...).  The plane indexing follows the dimension order specified in
 * the OME-XML Pixels element.
 */
public interface ImageWriter extends AutoCloseable {

    /**
     * Open the writer for output.
     *
     * @param path        output file path
     * @param omeXml      OME-XML metadata to embed in the output file
     * @param compression compression type: "Uncompressed", "LZW", or
     *                    "zlib" (matching Bio-Formats conventions)
     * @throws IOException if the file cannot be created
     */
    void open(Path path, String omeXml, String compression) throws IOException;

    /**
     * Set the current series for subsequent {@link #writePlane} calls.
     *
     * @param series zero-based series index
     * @throws IllegalArgumentException if the series is out of range
     *         for the OME-XML metadata
     */
    void setSeries(int series) throws IOException;

    /**
     * Write a single 2D plane of raw pixel data.
     *
     * <p>Planes must be written sequentially within each series
     * (index 0, 1, 2, ...).  The byte array format matches
     * {@link ImageReader#readPlane}: row-major order, native byte
     * order for the pixel type.
     *
     * @param planeIndex zero-based plane index within the current series
     * @param data       raw pixel bytes
     * @throws IOException if the data cannot be written
     */
    void writePlane(int planeIndex, byte[] data) throws IOException;

    /**
     * Returns the total number of bytes written to the output file
     * so far.  This is an approximation — it may not account for
     * headers or compression overhead precisely.
     */
    long getBytesWritten();

    /**
     * Close the writer and finalize the output file.
     *
     * <p>For OME-TIFF, this writes the OME-XML metadata into the
     * TIFF header.  The file is not valid until close completes.
     */
    @Override
    void close() throws IOException;
}
