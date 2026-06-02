# BioImage gRPC API (local sessions)

How a client talks to the local gRPC microservice
(`BioImageGrpcService`).  It is a **session-capable, persistent-connection**
transport — the sibling of the Unix-domain-socket service — built around a
single bidirectional stream whose lifetime *is* the session-owning connection.

For operation arguments, the error model, access control, and pixel-data
conventions, see **[service-endpoints.md](service-endpoints.md)**.  For the
design rationale, see **DESIGN.md §10** (sessions) and **§11** (gRPC).

**Local-only.** The server binds the loopback interface (`127.0.0.1`) with no
TLS and no auth.  It is intended for a co-located client, exactly like the
socket transport.

## 1. The two planes

- **Control plane** — the gRPC bidirectional stream `Session`, carrying
  protobuf `ClientMsg` / `ServerMsg` envelopes.
- **Data plane** — for a `deposit`, the same **client-owned shared-memory
  region** the socket transport uses (a file the client creates, sizes, maps,
  and unlinks).  Pixel volumes never cross the wire; only the descriptor does.
  Inspect/stats results travel as JSON strings, plane/thumbnail as PNG bytes
  inline (fine for a local link).

## 2. Starting the server

```sh
jbang runner/bioimage_grpc.java --allow /dev/shm --allow /data/microscopy
jbang runner/bioimage_grpc.java --port 9000 --allow /dev/shm
```

Options: `--port <n>` (default `8723`) and the shared, repeatable
`--allow <path>` / `--deny <path>`.  As with the socket service you must
permit both the source-image directory and the shared-memory directory (e.g.
`--allow /dev/shm`).

Local development against the fat jar (the published `//DEPS` artifact won't
contain this class yet):

```sh
mill assembly
java -cp "out/assembly.dest/out.jar" \
    lab.kerrr.mcpbio.bioimageserver.BioImageGrpcService --allow /dev/shm --allow /data
```

The server logs `listening on grpc://127.0.0.1:<port>` to stderr.

## 3. The service

```proto
service BioImage {
  rpc Session(stream ClientMsg) returns (stream ServerMsg);
}
```

The full schema is `src/proto/bioimage.proto` (generated into the
`lab.kerrr.mcpbio.bioimageserver.grpc` Java package).  One `Session` stream =
one connection.  On open, the server sends a `Ready`.  `ClientMsg` is a
`oneof` of `OpenSession`, `CloseSession`, `InspectRequest`, `PlaneRequest`,
`StatsRequest`, `ThumbnailRequest`, `DepositRequest`, `ShutdownRequest`; each
carries a client-chosen `id` echoed on the reply.  `ServerMsg` is a `oneof` of
`Ready`, `SessionOpened`, `JsonResult`, `PngResult`, `Filled`, `Closed`,
`ShutdownOk`, `Error`.

- Slice selections (`channels`/`z`/`t`/`channel`) are the same **strings**
  used everywhere (`":"`, `"0,2"`, `"4:9"`); an **empty** string means "not
  provided", and the server then reports the missing parameter.
- Numeric/bool/enum fields are proto3 `optional` — leave them unset to take
  the documented default (e.g. omit `normalize` to get the default `true`).
- Every op carries either `handle` (session) or `path` (stateless); set
  exactly one.

## 4. Session flow

```
client → ClientMsg{open: {path:"/data/stack.czi"}}
server → ServerMsg{session_opened: {handle, summary_json}}     // SUMMARY metadata as JSON

client → ClientMsg{inspect: {handle, detail:"full"}}
server → ServerMsg{json: {json:"…"}}                            // full metadata as JSON

client → ClientMsg{plane: {handle, channel:"0", z:"0", t:"0"}}
server → ServerMsg{png: {png: <bytes>}}

client → ClientMsg{deposit: {handle, channels:"0", z:":", t:"0",
                             target:{kind:"file", path:"/dev/shm/bio", capacity_bytes:N}}}
server → ServerMsg{filled: {offset, total_bytes, plane_bytes, pixel_type, …, shape}}

client → ClientMsg{close: {handle}}
server → ServerMsg{closed: {handle}}
```

The `deposit` flow (dry-run sizing, region allocation, layout/interpretation
of the filled buffer) is identical to the socket transport — see
**[API-socket.md](API-socket.md) §5 and §7**, including the NumPy `memmap`
example.  The `Filled` message fields mirror the socket `filled` descriptor.

Stateless use (no session) is also supported: send any read op with `path`
set instead of `handle`.

## 5. Lifetime & disconnect

A handle is valid only on the stream that opened it.  When the client
half-closes the stream (`onCompleted`), cancels it, or the connection breaks,
the server **cancels any in-flight deposit and closes every session opened on
that stream** (releasing the kept-open reader).  There is no cross-stream
handle sharing and no idle handle left dangling after a disconnect.

Operations on a single handle are serialized server-side (Bio-Formats readers
are not thread-safe); a `deposit` runs asynchronously so the stream keeps
receiving (one deposit in flight per stream — a second returns
`invalid_argument`).

## 6. Errors

Failures come back as `ServerMsg{error: {error_kind, message}}` with the
`id` echoed.  `error_kind` is one of `access_denied`, `invalid_argument`,
`timeout`, `io_error` (service-endpoints.md §4), or `shutdown_refused` (§7).
An error ends that request; the stream stays open.

## 7. Shutdown

`ClientMsg{shutdown:{}}` asks the server to exit, honored **only when the
requester is the sole connected stream** (so one client can't tear the server
out from under others).  On success the server replies
`ServerMsg{shutdown_ok:{}}`, completes the stream, and stops; otherwise it
replies `error` with kind `shutdown_refused`.  As with any service the server
may vanish at any moment, so a robust client already tolerates the stream
ending unexpectedly.
