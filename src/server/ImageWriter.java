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
     * Suggest a target number of planes to bundle into each storage shard,
     * for writers that shard (OME-Zarr).  This is a <b>hint</b>, not a
     * command: the writer chooses the closest value that divides each
     * series' volume with low overshoot (clamped to 1…planes-per-volume),
     * and may further coarsen it to honor a global file-count cap.  Query
     * {@link #preferredBlockShape(int)} after {@link #open} to learn the
     * shard shape actually chosen for each series.
     *
     * <p>Must be called before {@link #open} to take effect.  The default
     * implementation ignores it (plane-addressed writers such as OME-TIFF do
     * not shard); {@link ZarrWriter} overrides it.
     *
     * @param planes suggested planes per shard (≥ 1)
     */
    default void suggestShardPlanes(int planes) {
        // No-op: non-sharding writers have no shard to size.
    }

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
     * Write a single 2D plane, supplying its position on every axis.
     *
     * <p>This is the coordinate-addressed counterpart to
     * {@link #writePlane(int, byte[])}.  The {@code c}/{@code z}/{@code t}
     * indices are <b>output-relative</b> — zero-based within the subset
     * actually being written, not the source file's indices.  They let a
     * writer place a plane by coordinate rather than by relying on the
     * OME-XML dimension order to interpret a flat plane index.
     *
     * <p>The default implementation ignores the coordinates and delegates
     * to {@link #writePlane(int, byte[])}, which is correct for writers
     * (such as OME-TIFF) that address planes by index through the OME-XML
     * dimension order.  Writers that address planes by coordinate (such as
     * OME-Zarr) override this method; for those writers the plain
     * {@link #writePlane(int, byte[])} may be unsupported.
     *
     * @param planeIndex zero-based plane index within the current series,
     *                   following the OME-XML dimension order
     * @param c          output-relative channel index
     * @param z          output-relative Z index
     * @param t          output-relative timepoint index
     * @param data       raw pixel bytes (see {@link #writePlane(int, byte[])})
     * @throws IOException if the data cannot be written
     */
    default void writePlane(int planeIndex, int c, int z, int t, byte[] data)
            throws IOException {
        writePlane(planeIndex, data);
    }

    /**
     * Hint: the shard's extent on the {@code (T, C, Z)} axes — how many
     * timepoints, channels, and Z-planes one storage shard spans (Y and X are
     * always the full plane).  The caller hands off one shard-aligned block at
     * a time via {@link #writeShardBlock}, so distinct blocks never touch the
     * same shard and may be written concurrently.
     *
     * <p>For a plane-addressed writer (OME-TIFF) the answer is {@code {1,1,1}}.
     * A writer that bundles planes into a larger storage unit (an OME-Zarr
     * shard) returns that unit's shape — which, depending on the data's shape,
     * may extend along Z, across channels ({@code [1,C,Z]}), or across time
     * ({@code [T,1,1]}).
     *
     * @param series zero-based output series index
     */
    default int[] preferredBlockShape(int series) {
        return new int[]{1, 1, 1};
    }

    /**
     * Write a shard-aligned block of {@code bt×bc×bz} planes in a single
     * operation, at output-relative start {@code (tStart, cStart, zStart)}.
     *
     * <p>{@code data} holds the planes in NGFF / OME-Zarr <b>TCZYX C-order</b>
     * (time slowest, then channel, then Z; each plane row-major with X
     * fastest), {@code data.length / (bt*bc*bz)} bytes per plane.  This is the
     * unit a parallel exporter hands off: distinct shard-aligned blocks address
     * disjoint storage, so they may be written concurrently.
     *
     * <p>The default implementation splits the block into planes (in TCZYX
     * order) and calls {@link #writePlane(int, int, int, int, byte[])}, which
     * is correct for plane-addressed writers.  A shard-addressed writer
     * (OME-Zarr) overrides this to store the whole block in one operation.
     *
     * @param tStart output-relative timepoint of the first plane
     * @param cStart output-relative channel of the first plane
     * @param zStart output-relative Z of the first plane
     * @param bt     number of timepoints in the block
     * @param bc     number of channels in the block
     * @param bz     number of Z-planes in the block
     * @param data   {@code bt*bc*bz} planes in TCZYX C-order
     * @throws IOException if the data cannot be written
     */
    default void writeShardBlock(int tStart, int cStart, int zStart,
                                 int bt, int bc, int bz, byte[] data)
            throws IOException {
        int planeLen = data.length / (bt * bc * bz);
        int idx = 0;
        for (int ti = 0; ti < bt; ti++) {
            for (int ci = 0; ci < bc; ci++) {
                for (int zi = 0; zi < bz; zi++) {
                    byte[] plane = new byte[planeLen];
                    System.arraycopy(data, idx * planeLen, plane, 0, planeLen);
                    writePlane(0, cStart + ci, zStart + zi, tStart + ti, plane);
                    idx++;
                }
            }
        }
    }

    /**
     * Warnings about the on-disk layout the writer chose at {@link #open}
     * time, to be surfaced to the caller alongside the export result.
     *
     * <p>The canonical case is an OME-Zarr export whose
     * {@link #suggestShardPlanes suggested} shard depth produced more files
     * than the writer's recommended file-count cap: the suggestion is honored
     * (the user asked for it), but the caller is told the cap was exceeded so
     * the choice is never silently lossy.  Valid only after {@link #open}.
     *
     * <p>The default implementation returns an empty list (writers with no
     * layout choices to report).
     */
    default java.util.List<String> layoutWarnings() {
        return java.util.List.of();
    }

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
