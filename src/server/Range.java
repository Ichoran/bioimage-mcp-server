package lab.kerrr.mcpbio.bioimageserver;

/**
 * A concrete, resolved, inclusive index range over one dimension.
 *
 * <p>A {@code Range} is always non-negative and in bounds: it is what a
 * {@link Slice} produces once resolved against a known dimension size.
 * Both endpoints are inclusive, so {@code count()} is {@code end - start + 1}
 * and is always at least 1 (empty selections are rejected during resolution,
 * not represented here).
 *
 * @param start first index (inclusive, &ge; 0)
 * @param end   last index (inclusive, &ge; {@code start})
 */
public record Range(int start, int end) {

    public Range {
        if (start < 0) {
            throw new IllegalArgumentException(
                    "resolved range start must be >= 0, got " + start);
        }
        if (end < start) {
            throw new IllegalArgumentException(
                    "resolved range end " + end + " is less than start " + start);
        }
    }

    /** A single-index range. */
    public static Range of(int index) { return new Range(index, index); }

    /** An explicit inclusive range. */
    public static Range of(int start, int end) { return new Range(start, end); }

    /** Number of indices in this range (always &ge; 1). */
    public int count() { return end - start + 1; }

    /** Expand to the array of indices it covers. */
    int[] toArray() {
        var arr = new int[count()];
        for (int i = 0; i < arr.length; i++) arr[i] = start + i;
        return arr;
    }
}
