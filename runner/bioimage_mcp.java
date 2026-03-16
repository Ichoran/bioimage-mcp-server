///usr/bin/env jbang "$0" "$@" ; exit $?

// =================================================================
// BioImage MCP Server — JBang runner
//
// This file is the user-facing entry point.  Install JBang
// (https://www.jbang.dev/), then run:
//
//     jbang bioimage-mcp.java
//
// For development against a local build, use one of:
//
//     mill run                          # via Mill directly
//     jbang --cp "$(mill show assembly | tr -d '"')" bioimage-mcp.java
//
// Customize allow/deny paths below to control which filesystem
// paths the server may access.  See DESIGN.md §5 for details.
// =================================================================

//REPOS mavencentral,ome=https://artifacts.openmicroscopy.org/artifactory/maven/
//DEPS com.github.ichoran:bioimage_mcp_server:0.1.0
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.20

import lab.kerrr.mcpbio.bioimageserver.BioImageMcpServer;

public class bioimage_mcp {
    public static void main(String[] args) {
        BioImageMcpServer.builder()
                // .allow("/data/microscopy")
                // .allow("/shared/lab-images")
                // .deny("/data/microscopy/private")
                .build()
                .run(args);
    }
}
