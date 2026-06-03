package lab.kerrr.mcpbio.bioimageserver;

import java.util.ArrayList;
import java.util.List;

/**
 * An unresolved selection over one dimension: a comma-separated list of
 * Python-style slice terms, resolved against a dimension size to a concrete
 * {@code int[]} of indices.
 *
 * <p>The wire form is a string (a bare JSON integer is also accepted for the
 * single-term single-index case).  Each comma-separated term is either a
 * single index or a half-open range:
 *
 * <ul>
 *   <li>{@code "9"} / {@code 9} — the single index 9</li>
 *   <li>{@code "-9"} — the 9th index from the end ({@code size - 9})</li>
 *   <li>{@code "2:4"} — the half-open range {@code [2,4)} → {2,3}</li>
 *   <li>{@code "5:"} — {@code [5, size)}</li>
 *   <li>{@code ":-3"} — {@code [0, size-3)} (all but the last 3)</li>
 *   <li>{@code ":"} — {@code [0, size)} (all)</li>
 *   <li>{@code "1,2,6"} — the indices 1, 2, 6</li>
 *   <li>{@code "4:9,11:"} — 4…8 followed by 11…end</li>
 * </ul>
 *
 * <p>Terms are concatenated <b>in order</b>, and the result <b>may repeat an
 * index</b> (e.g. {@code "3,3"} or {@code "0:2,1:3"}).  That is intentional:
 * if a caller asks to read a plane twice, that is the caller's prerogative.
 *
 * <p><b>Strict bounds (deviation from Python).</b>  An <em>explicit</em> index
 * or bound that resolves outside {@code [0, size]} is an error rather than
 * being silently clamped — scientific software must not quietly truncate a
 * selection.  Open ends ({@code 5:}, {@code :}, {@code :-3}) still clamp to the
 * dimension size, since the omitted bound <em>is</em> the size.
 *
 * <p><b>No empty terms.</b>  A term that resolves to zero indices (e.g.
 * {@code "4:4"}, or {@code "5:"} when {@code size <= 5}) is an error.  Combined
 * with "a missing parameter is an error" at the call site, a caller can never
 * silently select nothing — or silently select everything by omission;
 * {@code ":"} must be written explicitly.
 *
 * <p>Step slices ({@code a:b:c}) are not supported.
 */
public final class Slice {

    /** One term: a single index, or a half-open range with optional open ends. */
    private record Term(boolean single, int index,
                        boolean startOpen, int start,
                        boolean endOpen, int end) {}

    private final List<Term> terms;

    private Slice(List<Term> terms) {
        this.terms = terms;
    }

    /** A single-index slice. */
    public static Slice single(int index) {
        return new Slice(List.of(new Term(true, index, false, 0, false, 0)));
    }

    /** The full slice, {@code ":"}. */
    public static Slice all() {
        return new Slice(List.of(new Term(false, 0, true, 0, true, 0)));
    }

    /**
     * Parse a wire value into a {@code Slice}.  Accepts a JSON number (single
     * index) or a string of comma-separated slice terms.
     *
     * @throws IllegalArgumentException if {@code value} is null or malformed
     */
    public static Slice parse(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(
                    name + " is required (use \":\" to select all)");
        }
        if (value instanceof Number n) {
            return single(n.intValue());
        }
        String raw = value.toString().trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException(
                    name + ": empty selection (use \":\" to select all)");
        }
        var terms = new ArrayList<Term>();
        for (String part : raw.split(",", -1)) {
            terms.add(parseTerm(part.trim(), name, raw));
        }
        return new Slice(terms);
    }

    private static Term parseTerm(String s, String name, String whole) {
        if (s.isEmpty()) {
            throw new IllegalArgumentException(
                    name + ": empty term in '" + whole + "'");
        }
        int colon = s.indexOf(':');
        if (colon < 0) {
            return new Term(true, parseInt(s, name), false, 0, false, 0);
        }
        if (s.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException(
                    name + ": step slices (a:b:c) are not supported, got '" + s + "'");
        }
        String a = s.substring(0, colon).trim();
        String b = s.substring(colon + 1).trim();
        boolean so = a.isEmpty();
        boolean eo = b.isEmpty();
        return new Term(false, 0,
                so, so ? 0 : parseInt(a, name),
                eo, eo ? 0 : parseInt(b, name));
    }

    private static int parseInt(String s, String name) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    name + ": not a valid index or slice: '" + s + "'");
        }
    }

    /** True if this is exactly {@code ":"} (a single, fully-open term). */
    public boolean isAll() {
        if (terms.size() != 1) return false;
        Term t = terms.get(0);
        return !t.single() && t.startOpen() && t.endOpen();
    }

    /**
     * Resolve against a dimension size to the concrete indices selected, in
     * order, possibly with repeats.
     *
     * @param size the dimension size (number of indices, &ge; 1)
     * @param name dimension name for error messages (e.g. "z")
     * @throws IllegalArgumentException if an explicit bound is out of range or
     *         a term resolves to empty
     */
    public int[] resolve(int size, String name) {
        var out = new ArrayList<Integer>();
        for (Term t : terms) {
            if (t.single()) {
                int k = t.index() < 0 ? size + t.index() : t.index();
                if (k < 0 || k >= size) {
                    throw new IllegalArgumentException(
                            name + " index " + t.index()
                            + " is out of range for size " + size);
                }
                out.add(k);
            } else {
                int s = t.startOpen() ? 0
                        : (t.start() < 0 ? size + t.start() : t.start());
                int e = t.endOpen() ? size
                        : (t.end() < 0 ? size + t.end() : t.end());
                if (!t.startOpen() && (s < 0 || s > size)) {
                    throw new IllegalArgumentException(
                            name + " start " + t.start()
                            + " is out of range for size " + size);
                }
                if (!t.endOpen() && (e < 0 || e > size)) {
                    throw new IllegalArgumentException(
                            name + " end " + t.end()
                            + " is out of range for size " + size);
                }
                if (s >= e) {
                    throw new IllegalArgumentException(
                            name + " selection '" + describe()
                            + "' is empty for size " + size);
                }
                for (int i = s; i < e; i++) out.add(i);
            }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /**
     * Resolve to a contiguous, strictly-ascending {@link Range}.  Used where
     * downstream code requires a contiguous span (e.g. OME-TIFF export, whose
     * writer rebuilds {@code TiffData} for a range).
     *
     * @throws IllegalArgumentException if the resolved indices are not a
     *         contiguous ascending run (e.g. "1,3" or "5:2")
     */
    public Range resolveContiguous(int size, String name) {
        int[] idx = resolve(size, name);
        for (int i = 1; i < idx.length; i++) {
            if (idx[i] != idx[i - 1] + 1) {
                throw new IllegalArgumentException(
                        name + " selection '" + describe() + "' must be a single "
                        + "contiguous range here (no gaps or repeats)");
            }
        }
        return new Range(idx[0], idx[idx.length - 1]);
    }

    /**
     * Resolve to exactly one index.  Used by single-plane selectors.
     *
     * @throws IllegalArgumentException if the selection is not a single index
     */
    public int resolveSingle(int size, String name) {
        int[] idx = resolve(size, name);
        if (idx.length != 1) {
            throw new IllegalArgumentException(
                    name + " must select exactly one index, but '" + describe()
                    + "' selects " + idx.length);
        }
        return idx[0];
    }

    /** Human-readable form of the original selection, for error messages. */
    public String describe() {
        var sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) sb.append(',');
            Term t = terms.get(i);
            if (t.single()) {
                sb.append(t.index());
            } else {
                if (!t.startOpen()) sb.append(t.start());
                sb.append(':');
                if (!t.endOpen()) sb.append(t.end());
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() { return "Slice(" + describe() + ")"; }
}
