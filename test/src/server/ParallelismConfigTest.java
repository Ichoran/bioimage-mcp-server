package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the {@code --parallelism} spec resolver and wiring. */
class ParallelismConfigTest {

    @Test
    void integerSpecIsThreadCount() {
        assertEquals(8, BioImageService.resolveParallelism("8", 16));
        assertEquals(1, BioImageService.resolveParallelism("1", 16));
    }

    @Test
    void decimalSpecIsFractionRoundedUp() {
        assertEquals(5, BioImageService.resolveParallelism("0.334", 12)); // ceil(4.008)
        assertEquals(2, BioImageService.resolveParallelism("0.334", 3));  // ceil(1.002)
        assertEquals(1, BioImageService.resolveParallelism("0.1", 4));    // ceil(0.4)
        assertEquals(8, BioImageService.resolveParallelism("0.5", 16));
        assertEquals(16, BioImageService.resolveParallelism("1.0", 16));
    }

    @Test
    void neverBelowOne() {
        assertEquals(1, BioImageService.resolveParallelism("0.001", 1));
    }

    @Test
    void rejectsNonPositiveOrUnparsable() {
        assertThrows(IllegalArgumentException.class,
                () -> BioImageService.resolveParallelism("0", 8));
        assertThrows(IllegalArgumentException.class,
                () -> BioImageService.resolveParallelism("-1", 8));
        assertThrows(IllegalArgumentException.class,
                () -> BioImageService.resolveParallelism("0.0", 8));
        assertThrows(NumberFormatException.class,
                () -> BioImageService.resolveParallelism("abc", 8));
    }

    @Test
    void builderAndCliSetParallelism() throws Exception {
        var svc = BioImageService.builder().parallelism(3).build();
        assertEquals(3, svc.parallelism());
        svc.applyCliArgs(new String[]{"--parallelism", "7"});
        assertEquals(7, svc.parallelism());
    }

    @Test
    void defaultIsAtLeastOne() {
        assertTrue(BioImageService.defaultParallelism() >= 1);
    }
}
