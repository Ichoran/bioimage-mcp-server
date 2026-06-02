package lab.kerrr.mcpbio.bioimageserver;

/**
 * Describes the raw pixel buffer written into a client-owned shared-memory
 * region by {@link BioImageService#deposit}.
 *
 * <p>The descriptor is fully self-describing: a client that has the region
 * mapped can interpret every byte from these fields alone, without having
 * inspected the source file.  Pixels are written in <b>C-order with X
 * fastest and T slowest</b> — the element at {@code (t, z, c, y, x)} (all
 * indices relative to the deposited selection, not the source file) lives
 * at byte offset
 * <pre>
 *   ((((t*sizeZ + z)*sizeC + c)*sizeY + y)*sizeX + x) * bytesPerSample
 * </pre>
 * Each {@code readPlane} result is one {@code (t,z,c)} plane of
 * {@code sizeY*sizeX} samples in row-major order, copied verbatim.
 *
 * <p>Bytes are written in the source file's native order;
 * {@link #littleEndian} reports which that is (not forced).
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
        int sizeT) {

    /** The fixed axis order of the buffer, outermost (slowest) first. */
    public static final java.util.List<String> AXIS_ORDER =
            java.util.List.of("t", "z", "c", "y", "x");
}
