package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the {@link Slice} selection grammar and resolution. */
class SliceTest {

    private static int[] r(String spec, int size) {
        return Slice.parse(spec, "x").resolve(size, "x");
    }

    // ---- single index ----

    @Test
    void singleIndex() {
        assertArrayEquals(new int[] {9}, r("9", 20));
    }

    @Test
    void singleFromNumber() {
        assertArrayEquals(new int[] {7}, Slice.parse(7, "x").resolve(10, "x"));
    }

    @Test
    void negativeSingleCountsFromEnd() {
        assertArrayEquals(new int[] {9}, r("-1", 10));
        assertArrayEquals(new int[] {1}, r("-9", 10));
    }

    @Test
    void singleOutOfRangeRejected() {
        assertThrows(IllegalArgumentException.class, () -> r("9", 5));
        assertThrows(IllegalArgumentException.class, () -> r("-11", 10));
    }

    // ---- half-open ranges ----

    @Test
    void halfOpenRange() {
        assertArrayEquals(new int[] {2, 3}, r("2:4", 10));
    }

    @Test
    void openStart() {
        assertArrayEquals(new int[] {0, 1, 2}, r(":3", 10));
    }

    @Test
    void openEnd() {
        assertArrayEquals(new int[] {5, 6, 7, 8, 9}, r("5:", 10));
    }

    @Test
    void fullColon() {
        assertArrayEquals(new int[] {0, 1, 2, 3}, r(":", 4));
        assertTrue(Slice.parse(":", "x").isAll());
        assertTrue(Slice.all().isAll());
        assertFalse(Slice.parse("0:", "x").isAll());
        assertFalse(Slice.parse("9", "x").isAll());
    }

    @Test
    void negativeEnd() {
        // [0, size-3) on size 10 → 0..6
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6}, r(":-3", 10));
    }

    @Test
    void negativeStart() {
        assertArrayEquals(new int[] {7, 8, 9}, r("-3:", 10));
    }

    // ---- lists ----

    @Test
    void indexList() {
        assertArrayEquals(new int[] {1, 2, 6}, r("1,2,6", 10));
    }

    @Test
    void listOfRanges() {
        assertArrayEquals(new int[] {4, 5, 8, 9}, r("4:6,8:", 10));
    }

    @Test
    void repeatsArePreserved() {
        assertArrayEquals(new int[] {3, 3}, r("3,3", 10));
        assertArrayEquals(new int[] {0, 1, 1, 2}, r("0:2,1:3", 10));
    }

    // ---- empty / strict-bounds errors ----

    @Test
    void emptyRangeRejected() {
        assertThrows(IllegalArgumentException.class, () -> r("4:4", 10));
        assertThrows(IllegalArgumentException.class, () -> r("5:", 5));
    }

    @Test
    void emptyStringRejected() {
        assertThrows(IllegalArgumentException.class, () -> Slice.parse("", "x"));
    }

    @Test
    void emptyTermInListRejected() {
        assertThrows(IllegalArgumentException.class, () -> Slice.parse("1,,2", "x"));
    }

    @Test
    void nullRejected() {
        assertThrows(IllegalArgumentException.class, () -> Slice.parse(null, "x"));
    }

    @Test
    void explicitBoundOutOfRangeRejectedNotClamped() {
        assertThrows(IllegalArgumentException.class, () -> r("2:100", 10));
        assertThrows(IllegalArgumentException.class, () -> r("15", 10));
    }

    @Test
    void stepSliceRejected() {
        assertThrows(IllegalArgumentException.class, () -> Slice.parse("1:2:3", "x"));
    }

    // ---- resolveContiguous ----

    @Test
    void resolveContiguousReturnsRange() {
        var range = Slice.parse("2:5", "x").resolveContiguous(10, "x");
        assertEquals(2, range.start());
        assertEquals(4, range.end());
    }

    @Test
    void resolveContiguousRejectsGaps() {
        assertThrows(IllegalArgumentException.class,
                () -> Slice.parse("1,3", "x").resolveContiguous(10, "x"));
    }

    // ---- resolveSingle ----

    @Test
    void resolveSingleAcceptsOneIndex() {
        assertEquals(2, Slice.parse("2", "x").resolveSingle(10, "x"));
        assertEquals(0, Slice.parse("0:1", "x").resolveSingle(10, "x"));
        assertEquals(9, Slice.parse("-1", "x").resolveSingle(10, "x"));
    }

    @Test
    void resolveSingleRejectsMultiple() {
        assertThrows(IllegalArgumentException.class,
                () -> Slice.parse(":", "x").resolveSingle(5, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> Slice.parse("0,1", "x").resolveSingle(10, "x"));
    }
}
