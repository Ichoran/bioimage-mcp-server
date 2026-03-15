package lab.kerrr.mcpbio.bioimageserver;

import java.util.List;

/**
 * Top-level metadata for an image file, as returned by {@code inspect_image}.
 *
 * <p>Contains file-level information and a list of series.  Multi-series
 * formats (e.g. LIF, CZI with scenes) will have multiple entries in
 * {@link #allSeries}; single-image formats will have exactly one.
 *
 * @param formatName           human-readable format name (e.g. "Zeiss CZI")
 * @param allSeries            brief info for every series in the file
 *                             (at minimum: name, dimensions)
 * @param detailedSeriesIndex  which series was fully populated (-1 if all)
 * @param detailedSeries       the fully-populated series info for the
 *                             requested series
 * @param detailLevel          the detail level that was requested
 * @param omittedMetadataBytes approximate bytes of metadata that were
 *                             available but not returned (0 at full detail)
 */
public record ImageMetadata(
        String formatName,
        List<SeriesSummary> allSeries,
        int detailedSeriesIndex,
        SeriesInfo detailedSeries,
        DetailLevel detailLevel,
        long omittedMetadataBytes) {

    public ImageMetadata {
        if (formatName == null || formatName.isBlank()) {
            throw new IllegalArgumentException("formatName must not be blank");
        }
        allSeries = allSeries == null ? List.of() : List.copyOf(allSeries);
        if (detailedSeries == null) {
            throw new IllegalArgumentException("detailedSeries must not be null");
        }
    }

    /** The level of metadata detail requested. */
    public enum DetailLevel { SUMMARY, STANDARD, FULL }

    /**
     * Minimal identifying information for a series, used in the
     * {@link #allSeries} listing so the user can see what's in the
     * file without requesting full metadata for every series.
     *
     * @param index  zero-based series index
     * @param name   series name, or null if unnamed
     * @param sizeX  width in pixels
     * @param sizeY  height in pixels
     * @param sizeZ  number of Z slices
     * @param sizeC  number of channels
     * @param sizeT  number of timepoints
     */
    public record SeriesSummary(
            int index,
            String name,
            int sizeX, int sizeY, int sizeZ, int sizeC, int sizeT) {}
}
