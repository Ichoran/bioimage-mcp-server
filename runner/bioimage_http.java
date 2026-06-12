///usr/bin/env jbang "$0" "$@" ; exit $?

// =================================================================
// BioImage HTTP microservice — JBang runner
//
// Sibling of bioimage_mcp.java.  Same image-serving core
// (BioImageService), different transport: a plain HTTP server
// instead of MCP/stdio.  Launching it is just running this file
// instead of the MCP one.
//
//     jbang bioimage_http.java                 # all interfaces, port 8722
//     jbang bioimage_http.java --port 9000     # a 2nd local user picks a port
//     jbang bioimage_http.java --bind 127.0.0.1        # loopback only
//     jbang bioimage_http.java --require-token         # demand an auth token
//     jbang bioimage_http.java --allow /data/microscopy
//
// SECURITY: HTTP is the EXPOSED transport — by default it binds all
// interfaces with no auth (that is the point).  To narrow it: --bind
// <addr> restricts the interface, and --require-token demands a token on
// the operation endpoints (the token is printed to stderr at startup;
// /health stays open).  See DESIGN.md §5.
//
// For development against a local build, run the class straight from
// the fat jar (the published //DEPS artifact below won't contain
// newly-added classes/methods yet, and would shadow the local copy on
// jbang's classpath):
//
//     mill assembly
//     java -cp out/assembly.dest/out.jar \
//         lab.kerrr.mcpbio.bioimageserver.BioImageHttpService --port 8722
//
// Once a release containing this class is published, the one-line
// `jbang bioimage_http.java` form below works for end users.
//
// Customize allow/deny paths below to control which filesystem
// paths the server may access.  See DESIGN.md §5 for details.
// =================================================================

//JAVA 21
//REPOS mavencentral,ome=https://artifacts.openmicroscopy.org/artifactory/maven/,unidata=https://artifacts.unidata.ucar.edu/all/
//DEPS com.github.ichoran:bioimage_mcp_server:0.4.0
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.20

import lab.kerrr.mcpbio.bioimageserver.BioImageHttpService;

public class bioimage_http {
    public static void main(String[] args) {
        BioImageHttpService.builder()
                // .allow("/data/microscopy")
                // .allow("/shared/lab-images")
                // .deny("/data/microscopy/private")
                // .port(8722)
                // .bind("127.0.0.1")       // restrict to one interface
                // .requireToken(true)      // demand an auth token
                .build()
                .run(args);
    }
}
