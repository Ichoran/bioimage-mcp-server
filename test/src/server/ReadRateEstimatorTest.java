package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReadRateEstimatorTest {

    @Test
    void canAffordReturnsTrueWithInsufficientData() {
        var est = new ReadRateEstimator(1_000_000_000L, 0.90);
        // No observations yet — optimistic
        assertTrue(est.canAfford(100));

        // One observation — still not enough
        est.recordStep(1, 500_000_000L);
        assertTrue(est.canAfford(100));
    }

    @Test
    void canAffordReturnsFalseWhenClearlyOverBudget() {
        // Budget: 1 second.  Each step takes ~0.4s.
        var est = new ReadRateEstimator(1_000_000_000L, 0.90);
        est.recordStep(1, 400_000_000L);  // 0.4s for first plane
        est.recordStep(1, 800_000_000L);  // 0.4s for second plane

        // 0.8s elapsed, asking for 1 more plane (~0.4s) → ~1.2s total
        // With 90th percentile, should say no
        assertFalse(est.canAfford(1));
    }

    @Test
    void canAffordReturnsTrueWhenPlentyOfBudget() {
        // Budget: 10 seconds.  Each step takes ~0.001s.
        var est = new ReadRateEstimator(10_000_000_000L, 0.90);
        est.recordStep(1, 1_000_000L);   // 1ms
        est.recordStep(1, 2_000_000L);   // 1ms
        est.recordStep(1, 3_000_000L);   // 1ms

        // 3ms elapsed, 10s budget, asking for 10 more → ~13ms total
        assertTrue(est.canAfford(10));
    }

    @Test
    void canAffordScalesWithBatchSize() {
        // Budget: 100ms. Steady rate of 10ms/plane.
        var est = new ReadRateEstimator(100_000_000L, 0.90);
        est.recordStep(1, 10_000_000L);
        est.recordStep(1, 20_000_000L);
        est.recordStep(1, 30_000_000L);

        // 30ms elapsed. 1 more plane (~40ms total) should fit
        assertTrue(est.canAfford(1));

        // But 10 more planes (~130ms total) should not
        assertFalse(est.canAfford(10));
    }

    @Test
    void rejectsInvalidBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReadRateEstimator(0, 0.90));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadRateEstimator(-1, 0.90));
    }

    @Test
    void rejectsInvalidConfidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReadRateEstimator(1_000_000L, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadRateEstimator(1_000_000L, 1.0));
    }

    @Test
    void tracksTotalPlanesAndObservations() {
        var est = new ReadRateEstimator(1_000_000_000L, 0.90);
        est.recordStep(5, 100_000_000L);
        est.recordStep(5, 200_000_000L);

        assertEquals(10, est.totalPlanesRead());
        assertEquals(2, est.observationCount());
        assertEquals(200_000_000L, est.totalElapsedNanos());
    }
}
