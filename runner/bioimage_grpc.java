///usr/bin/env jbang "$0" "$@" ; exit $?

// =================================================================
// BioImage gRPC microservice — JBang runner
//
// Sibling of bioimage_mcp.java / bioimage_http.java / bioimage_socket.java.
// Same image core (BioImageService); transport is a local gRPC server
// exposing one bidirectional `Session` stream.  Like the socket adapter
// it is session-capable: open an image once, reuse the handle for many
// reads, and the kept-open reader is closed when the stream ends.  Deposit
// pixel data still lands in a client-owned shared-memory region (see
// DESIGN.md §9); only control messages and small results cross the wire.
//
//     jbang bioimage_grpc.java                       # loopback :8723
//     jbang bioimage_grpc.java --port 9000
//     jbang bioimage_grpc.java --allow /dev/shm --allow /data
//
// The client must --allow (or root) both the source images and the
// shared-memory target directory (e.g. --allow /dev/shm).  The server
// binds the loopback interface only (local-only by design).
//
// For development against a local build, run the class straight from the
// fat jar (the published //DEPS artifact won't contain this class yet):
//
//     mill assembly
//     java -cp "out/assembly.dest/out.jar" \
//         lab.kerrr.mcpbio.bioimageserver.BioImageGrpcService \
//         --allow /dev/shm
// =================================================================

//REPOS mavencentral,ome=https://artifacts.openmicroscopy.org/artifactory/maven/
//DEPS com.github.ichoran:bioimage_mcp_server:0.3.0
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.20

import lab.kerrr.mcpbio.bioimageserver.BioImageGrpcService;

public class bioimage_grpc {
    public static void main(String[] args) {
        BioImageGrpcService.builder()
                // .allow("/dev/shm")
                // .allow("/data/microscopy")
                // .port(9000)
                .build()
                .run(args);
    }
}
