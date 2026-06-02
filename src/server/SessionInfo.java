package lab.kerrr.mcpbio.bioimageserver;

/**
 * Result of opening a session (see {@link BioImageService#openSession}).
 *
 * @param handle  the token a client passes as {@code handle} on subsequent
 *                operations to reuse the session's already-open reader
 * @param summary a SUMMARY-detail {@link ImageMetadata} snapshot, so the client
 *                immediately knows the file's series and dimensions without a
 *                separate inspect call
 */
public record SessionInfo(String handle, ImageMetadata summary) {}
