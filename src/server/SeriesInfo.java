package lab.kerrr.mcpbio.bioimageserver;

import java.util.List;
import java.util.Map;

/**
 * Metadata for a single image series.
 *
 * <p>The level of detail populated depends on the requested detail
 * level (summary / standard / full).  Fields that are not populated
 * at a given detail level will be null or empty, never fabricated.
 *
 * @param name              series name, or null if unnamed
 * @param sizeX             width in pixels
 * @param sizeY             height in pixels
 * @param sizeZ             number of Z slices
 * @param sizeC             number of channels
 * @param sizeT             number of timepoints
 * @param pixelType         pixel data type
 * @param dimensionOrder    dimension storage order (e.g. "XYCZT")
 * @param physicalSizeX     physical pixel size in X, or null if unknown
 * @param physicalSizeY     physical pixel size in Y, or null if unknown
 * @param physicalSizeZ     physical Z step size, or null if unknown
 * @param channels          per-channel metadata (empty list at summary level
 *                          if channel names are not available)
 * @param instrument        instrument/objective metadata, or null
 * @param acquisitionDate   acquisition timestamp as ISO-8601 string, or null
 * @param extraMetadata     additional key-value metadata (full detail only)
 */
public record SeriesInfo(
        String name,
        int sizeX,
        int sizeY,
        int sizeZ,
        int sizeC,
        int sizeT,
        PixelType pixelType,
        String dimensionOrder,
        PixelSize physicalSizeX,
        PixelSize physicalSizeY,
        PixelSize physicalSizeZ,
        List<ChannelInfo> channels,
        InstrumentInfo instrument,
        String acquisitionDate,
        Map<String, String> extraMetadata) {

    public SeriesInfo {
        if (sizeX < 1) throw new IllegalArgumentException("sizeX must be positive");
        if (sizeY < 1) throw new IllegalArgumentException("sizeY must be positive");
        if (sizeZ < 1) throw new IllegalArgumentException("sizeZ must be positive");
        if (sizeC < 1) throw new IllegalArgumentException("sizeC must be positive");
        if (sizeT < 1) throw new IllegalArgumentException("sizeT must be positive");
        channels = channels == null ? List.of() : List.copyOf(channels);
        extraMetadata = extraMetadata == null ? Map.of() : Map.copyOf(extraMetadata);
    }

    /** Total number of 2D planes in this series. */
    public int planeCount() {
        return sizeZ * sizeC * sizeT;
    }

    /** Total number of pixels in this series. */
    public long totalPixels() {
        return (long) sizeX * sizeY * sizeZ * sizeC * sizeT;
    }

    /** Approximate raw data size in bytes. */
    public long rawDataBytes() {
        return totalPixels() * pixelType.bytesPerPixel();
    }
}
