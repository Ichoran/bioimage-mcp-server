package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntensityStatsTest {

    @Test
    void rejectsHistogramLengthMismatch() {
        // 4 bin edges but 2 counts (should be 3 counts for 4 edges)
        assertThrows(IllegalArgumentException.class, () ->
                new IntensityStats(0, 0, 255, 128, 50, 130,
                        new double[]{0, 85, 170, 255}, new long[]{100, 200},
                        0, 0, 1.0, false, 1.0));
    }

    @Test
    void acceptsCorrectHistogramLengths() {
        var stats = new IntensityStats(0, 0, 255, 128, 50, 130,
                new double[]{0, 128, 256}, new long[]{400, 600},
                0, 0, 1.0, false, 1.0);
        assertEquals(2, stats.histogramCounts().length);
        assertEquals(3, stats.histogramBinEdges().length);
    }

    @Test
    void histogramArraysAreDefensivelyCopied() {
        var edges = new double[]{0, 128, 256};
        var counts = new long[]{400, 600};
        var stats = new IntensityStats(0, 0, 255, 128, 50, 130,
                edges, counts, 0, 0, 1.0, false, 1.0);

        edges[0] = 999;
        counts[0] = 999;
        assertEquals(0, stats.histogramBinEdges()[0], "edge array must be a copy");
        assertEquals(400, stats.histogramCounts()[0], "counts array must be a copy");
    }
}
