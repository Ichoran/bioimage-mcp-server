package lab.kerrr.mcpbio.bioimageserver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP server that provides Bio-Formats reader and metadata capability
 * for AI agents working with microscopy image data.
 */
public class BioImageMcpServer {

    /** Paths the server must never access (highest priority). */
    public final List<Path> denyList = new ArrayList<>();

    /** Paths explicitly permitted by the server configuration. */
    public final List<Path> allowList = new ArrayList<>();

    /** Paths declared by the MCP client as in-scope for the session. */
    public final List<Path> clientRoots = new ArrayList<>();

    public static void main(String[] args) {
        // TODO: Initialize and start the MCP server
    }
}
