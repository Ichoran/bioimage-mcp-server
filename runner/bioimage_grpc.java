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
//     jbang bioimage_grpc.java                       # loopback, ephemeral port
//     jbang bioimage_grpc.java --port 8723           # pin a fixed port
//     jbang bioimage_grpc.java --instance lab2       # a 2nd instance, same user
//     jbang bioimage_grpc.java --allow /dev/shm --allow /data
//
// The client must --allow (or root) both the source images and the
// shared-memory target directory (e.g. --allow /dev/shm).
//
// SECURITY: loopback is machine-local but NOT user-only, so by default the
// server requires a per-user auth token and binds an EPHEMERAL port.  It
// writes {port, token} to a per-user descriptor file ($XDG_RUNTIME_DIR/
// bioimage-grpc.json, mode 0600) that the same user's client reads to
// connect — so other local users are kept out and several users can run
// their own instances without colliding on a fixed port.  Pass --insecure
// to drop the token (any local user may then connect).  See DESIGN.md §11.
//
// For development against a local build, run the class straight from the
// fat jar (the published //DEPS artifact won't contain this class yet):
//
//     mill assembly
//     java -cp "out/assembly.dest/out.jar" \
//         lab.kerrr.mcpbio.bioimageserver.BioImageGrpcService \
//         --allow /dev/shm
// =================================================================

//JAVA 21
//REPOS mavencentral,ome=https://artifacts.openmicroscopy.org/artifactory/maven/,unidata=https://artifacts.unidata.ucar.edu/all/
//DEPS com.github.ichoran:bioimage_mcp_server:0.4.0
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.20

import lab.kerrr.mcpbio.bioimageserver.BioImageGrpcService;

public class bioimage_grpc {
    public static void main(String[] args) {
        BioImageGrpcService.builder()
                // .allow("/dev/shm")
                // .allow("/data/microscopy")
                // .port(8723)              // pin a fixed port (default: ephemeral)
                // .instance("lab2")        // distinct descriptor for a 2nd instance
                // .requireToken(false)     // drop the per-user token (insecure)
                .build()
                .run(args);
    }
}
