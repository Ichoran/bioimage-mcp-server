package lab.kerrr.mcpbio.bioimageserver;

/**
 * The outcome of a tool invocation — either a successful value or a
 * structured error.
 *
 * <p>Errors are first-class results, not exceptions.  Every tool returns
 * a {@code ToolResult}, and callers pattern-match on the variants rather
 * than catching exceptions.  This keeps the failure path as carefully
 * handled as the success path.
 *
 * @param <T> the type of value produced on success
 */
public sealed interface ToolResult<T> {

    /** The tool completed successfully and produced a value. */
    record Success<T>(T value) implements ToolResult<T> {}

    /**
     * The tool failed with a structured error.
     *
     * @param kind    machine-readable error category
     * @param message human-readable description (suitable for the LLM)
     * @param cause   underlying exception, or null if not applicable
     */
    record Failure<T>(ErrorKind kind, String message,
                      Throwable cause) implements ToolResult<T> {}

    /** Error categories for tool failures. */
    enum ErrorKind {
        /** The path is not permitted by the access policy. */
        ACCESS_DENIED,
        /** A tool parameter is invalid (e.g. series index out of range). */
        INVALID_ARGUMENT,
        /** An I/O error occurred (file not found, network failure, corrupt file, etc.). */
        IO_ERROR,
        /** The operation did not complete within the time budget. */
        TIMEOUT
    }

    // ---- Factory methods ----

    static <T> Success<T> success(T value) {
        return new Success<>(value);
    }

    static <T> Failure<T> accessDenied(String message) {
        return new Failure<>(ErrorKind.ACCESS_DENIED, message, null);
    }

    static <T> Failure<T> invalidArgument(String message) {
        return new Failure<>(ErrorKind.INVALID_ARGUMENT, message, null);
    }

    static <T> Failure<T> ioError(String message, Throwable cause) {
        return new Failure<>(ErrorKind.IO_ERROR, message, cause);
    }

    static <T> Failure<T> timeout(String message) {
        return new Failure<>(ErrorKind.TIMEOUT, message, null);
    }
}
