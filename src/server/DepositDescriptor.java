package lab.kerrr.mcpbio.bioimageserver;

/**
 * Describes the raw pixel buffer written into a client-owned shared-memory
 * region by {@link BioImageService#deposit}.
 *
 * <p>The descriptor is fully self-describing: a client that has the region
 * mapped can interpret every byte from these fields alone, without having
 * inspected the source file.  Pixels are written in <b>C-order with X
 * fastest and T slowest</b>, in the NGFF / OME-Zarr canonical axis order
 * {@code [t, c, z, y, x]} — the element at {@code (t, c, z, y, x)} (all
 * indices relative to the deposited selection, not the source file) lives
 * at byte offset
 * <pre>
 *   ((((t*sizeC + c)*sizeZ + z)*sizeY + y)*sizeX + x) * bytesPerSample
 * </pre>
 * Each {@code readPlane} result is one {@code (t,c,z)} plane of
 * {@code sizeY*sizeX} samples in row-major order, copied verbatim.  This is
 * the order an OME-Zarr / NGFF consumer (napari, zarr, dask) expects, so a
 * mapped region drops in without a transpose.
 *
 * <p>Bytes are written in the source file's native order;
 * {@link #littleEndian} reports which that is (not forced).
 *
 * <p><b>Interpretability of an arbitrary subset.</b>  A deposit can select an
 * arbitrary subset of a 5D image — and on the channel/Z/T axes that subset can
 * be a non-contiguous, reordered, even repeated index list (e.g.
 * {@code "0,2,5"}).  The {@code sizeN} counts alone are therefore not enough to
 * interpret the byte stream: a client also needs to know <em>which</em> source
 * index sits at each buffer position on each axis.  {@link #selection} reports
 * exactly that — the resolved source indices delivered on <b>every</b> axis (in
 * buffer order), so a mapped region is fully interpretable from the descriptor
 * alone.  X and Y are always the full extent here (Bio-Formats is plane-based),
 * but they are reported anyway so the format is general: a more-capable server
 * that crops X/Y fills in real sub-selections through the identical field.
 *
 * @param offset         byte offset of the data within the region (always 0)
 * @param totalBytes     total bytes written ({@code planeBytes * sizeC*sizeZ*sizeT})
 * @param planeBytes     bytes per 2D plane ({@code sizeX*sizeY*bytesPerSample})
 * @param pixelType      pixel type name (e.g. "uint16", "float")
 * @param bytesPerSample size of one sample in bytes
 * @param signed         whether samples are signed
 * @param littleEndian   true if multi-byte samples are little-endian
 * @param sizeX          width in pixels (X, fastest axis)
 * @param sizeY          height in pixels (Y)
 * @param sizeC          number of channels deposited (C)
 * @param sizeZ          number of Z-slices deposited (Z)
 * @param sizeT          number of timepoints deposited (T, slowest axis)
 * @param selection      per-axis resolved source indices (run-length encoded),
 *                       one entry per axis in {@link #AXIS_ORDER} order
 */
public record DepositDescriptor(
        long offset,
        long totalBytes,
        long planeBytes,
        String pixelType,
        int bytesPerSample,
        boolean signed,
        boolean littleEndian,
        int sizeX,
        int sizeY,
        int sizeC,
        int sizeZ,
        int sizeT,
        java.util.List<AxisSelection> selection) {

    /**
     * The fixed axis order of the buffer, outermost (slowest) first.
     * This is the NGFF / OME-Zarr canonical order {@code [t, c, z, y, x]}.
     */
    public static final java.util.List<String> AXIS_ORDER =
            java.util.List.of("t", "c", "z", "y", "x");

    /** A half-open run {@code [start, stop)} of source indices. */
    public record IndexRange(int start, int stop) {}

    /**
     * The source indices delivered on one axis, in buffer order, as a list of
     * half-open {@link IndexRange}s (run-length encoded).  Concatenating the
     * ranges yields the exact ordered index list; e.g. channels {@code "0,2,5"}
     * → {@code [[0,1),[2,3),[5,6)]}, a full Z of 21 → {@code [[0,21)]}.
     */
    public record AxisSelection(String axis, java.util.List<IndexRange> ranges) {}

    /**
     * Build the per-axis {@link #selection} for a deposit, in {@link #AXIS_ORDER}
     * order.  X and Y are the full plane extent; C/Z/T are the resolved index
     * arrays (run-length encoded, preserving order and repeats).
     */
    public static java.util.List<AxisSelection> selectionsFor(
            int[] ts, int[] cs, int[] zs, int sizeY, int sizeX) {
        return java.util.List.of(
                new AxisSelection("t", rle(ts)),
                new AxisSelection("c", rle(cs)),
                new AxisSelection("z", rle(zs)),
                new AxisSelection("y", java.util.List.of(new IndexRange(0, sizeY))),
                new AxisSelection("x", java.util.List.of(new IndexRange(0, sizeX))));
    }

    /** Run-length encode an ordered index list into half-open ranges. */
    static java.util.List<IndexRange> rle(int[] idx) {
        var out = new java.util.ArrayList<IndexRange>();
        int i = 0;
        while (i < idx.length) {
            int start = idx[i];
            int prev = idx[i];
            int j = i + 1;
            while (j < idx.length && idx[j] == prev + 1) {
                prev = idx[j];
                j++;
            }
            out.add(new IndexRange(start, prev + 1));
            i = j;
        }
        return out;
    }
}
