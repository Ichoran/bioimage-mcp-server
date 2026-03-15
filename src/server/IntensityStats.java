package lab.kerrr.mcpbio.bioimageserver;

/**
 * Intensity statistics for a single channel (or region) of image data.
 *
 * @param channel              channel index these stats describe
 * @param min                  minimum pixel value
 * @param max                  maximum pixel value
 * @param mean                 mean pixel value
 * @param stddev               standard deviation
 * @param median               median pixel value
 * @param histogramBinEdges    left edges of histogram bins (length = binCount + 1,
 *                             last entry is the right edge of the final bin)
 * @param histogramCounts      count per bin (length = binCount)
 * @param saturationFractionLow  fraction of pixels at the type minimum
 *                               (potential clipping)
 * @param saturationFractionHigh fraction of pixels at the type maximum
 *                               (saturation)
 * @param bitDepthUtilization  fraction of the type's dynamic range actually used
 *                             (range of data / range of type)
 * @param sampled              true if stats were computed on a subset of
 *                             the data (e.g. due to budget constraints)
 * @param sampledFraction      fraction of total pixels actually examined
 *                             (1.0 if not sampled)
 */
public record IntensityStats(
        int channel,
        double min,
        double max,
        double mean,
        double stddev,
        double median,
        double[] histogramBinEdges,
        long[] histogramCounts,
        double saturationFractionLow,
        double saturationFractionHigh,
        double bitDepthUtilization,
        boolean sampled,
        double sampledFraction) {

    public IntensityStats {
        if (histogramBinEdges == null || histogramCounts == null) {
            throw new IllegalArgumentException("histogram data must not be null");
        }
        if (histogramBinEdges.length != histogramCounts.length + 1) {
            throw new IllegalArgumentException(
                    "histogramBinEdges.length must be histogramCounts.length + 1");
        }
        // Defensive copies
        histogramBinEdges = histogramBinEdges.clone();
        histogramCounts = histogramCounts.clone();
    }
}
