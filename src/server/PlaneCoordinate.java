package lab.kerrr.mcpbio.bioimageserver;

/**
 * Identifies a single 2D plane within a multi-dimensional image by
 * its channel, Z-slice, and timepoint indices.
 *
 * @param channel   zero-based channel index
 * @param z         zero-based Z-slice index
 * @param timepoint zero-based timepoint index
 */
public record PlaneCoordinate(int channel, int z, int timepoint) {
    public PlaneCoordinate {
        if (channel < 0) throw new IllegalArgumentException("channel must be non-negative");
        if (z < 0) throw new IllegalArgumentException("z must be non-negative");
        if (timepoint < 0) throw new IllegalArgumentException("timepoint must be non-negative");
    }
}
