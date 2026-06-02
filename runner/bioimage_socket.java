///usr/bin/env jbang "$0" "$@" ; exit $?

// =================================================================
// BioImage shared-memory deposit microservice — JBang runner
//
// Sibling of bioimage_mcp.java / bioimage_http.java.  Same image
// core (BioImageService); transport is a persistent Unix-domain
// socket carrying newline-delimited JSON control messages, while
// pixel data is written into a client-owned shared-memory region
// (a tmpfs / /dev/shm file the client mmaps).  See DESIGN.md §9.
//
//     jbang bioimage_socket.java                       # default socket
//     jbang bioimage_socket.java --socket /run/bio.sock
//     jbang bioimage_socket.java --allow /dev/shm --allow /data
//
// The client must --allow (or root) both the source images and the
// shared-memory target directory (e.g. --allow /dev/shm).
//
// For development against a local build, run the class straight from
// the fat jar (the published //DEPS artifact won't contain this class
// yet, and would shadow the local copy on jbang's classpath):
//
//     mill assembly
//     java -cp out/assembly.dest/out.jar \
//         lab.kerrr.mcpbio.bioimageserver.BioImageSocketService \
//         --allow /dev/shm
// =================================================================

//REPOS mavencentral,ome=https://artifacts.openmicroscopy.org/artifactory/maven/
//DEPS com.github.ichoran:bioimage_mcp_server:0.2.0
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.20

import lab.kerrr.mcpbio.bioimageserver.BioImageSocketService;

public class bioimage_socket {
    public static void main(String[] args) {
        BioImageSocketService.builder()
                // .allow("/dev/shm")
                // .allow("/data/microscopy")
                // .socket("/run/user/1000/bioimage-deposit.sock")
                .build()
                .run(args);
    }
}
