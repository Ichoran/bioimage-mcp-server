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

    // ---- CancellableTask.Result → ToolResult conversion ----

    /**
     * Convert a {@link CancellableTask.Result} to a {@code ToolResult}.
     * Shared by all tool implementations.
     */
    static <T> ToolResult<T> unwrap(CancellableTask.Result<T> result) {
        return switch (result) {
            case CancellableTask.Result.Completed<T> c ->
                    ToolResult.success(c.value());
            case CancellableTask.Result.Failed<T> f ->
                    convertError(f.error());
            case CancellableTask.Result.TimedOut<T> t ->
                    ToolResult.timeout(
                        "Operation timed out after " + t.elapsed().toMillis()
                        + " ms (interrupted " + t.interruptsSent()
                        + " time(s), thread "
                        + (t.threadStillAlive() ? "still alive" : "terminated")
                        + ")");
        };
    }

    /**
     * Convert an exception from a tool's work lambda to a
     * {@code ToolResult.Failure} with an informative message.
     *
     * <p>Many Java exceptions (including {@link java.io.IOException}
     * subclasses from native I/O and Bio-Formats) can have a null
     * message.  This method always produces a non-null, descriptive
     * message that includes the exception class name when the message
     * is absent.
     */
    static <T> ToolResult<T> convertError(Throwable error) {
        if (error instanceof InterruptedException) {
            return ToolResult.timeout(
                    "Operation was interrupted (likely timed out)");
        }
        if (error instanceof IllegalArgumentException) {
            return ToolResult.invalidArgument(describeError(error));
        }
        if (error instanceof java.io.IOException) {
            return ToolResult.ioError(describeError(error), error);
        }
        return ToolResult.ioError(describeError(error), error);
    }

    /**
     * Build a human-readable description of an error, always non-null.
     * Includes the class name when the message is missing, and appends
     * the cause chain for context.
     */
    private static String describeError(Throwable error) {
        var sb = new StringBuilder();

        // Primary error: always include the class name for non-obvious
        // types, and always include whatever message is available.
        String msg = error.getMessage();
        String className = error.getClass().getName();
        String simpleName = error.getClass().getSimpleName();

        if (msg == null || msg.isBlank()) {
            sb.append(className).append(" (no detail message)");
        } else if (error instanceof java.io.IOException
                && !(error instanceof java.io.FileNotFoundException)) {
            // For IOException subclasses (EOFException, etc.), include
            // the class name so we know *which* I/O problem occurred.
            sb.append(simpleName).append(": ").append(msg);
        } else {
            sb.append(msg);
        }

        // Append cause chain (at most 3 levels) for additional context.
        Throwable cause = error.getCause();
        int depth = 0;
        while (cause != null && depth < 3) {
            String causeMsg = cause.getMessage();
            String causeClass = cause.getClass().getSimpleName();
            if (causeMsg != null && !causeMsg.isBlank()) {
                sb.append(" [caused by ").append(causeClass)
                  .append(": ").append(causeMsg).append("]");
            } else {
                sb.append(" [caused by ").append(cause.getClass().getName())
                  .append("]");
            }
            cause = cause.getCause();
            depth++;
        }

        return sb.toString();
    }
}
