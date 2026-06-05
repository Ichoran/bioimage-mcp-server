# Implementation Plan

Remove items as they are completed.  Add items when new work is
identified that deserves its own step.  Re-order when priorities change.

**Reference code:** A local clone of the Bio-Formats repository is at
`../../java/bioformats` (i.e. `~/Code/java/bioformats`).  Use it to
look up API details, pixel type constants, metadata accessors, etc.

## Phase 9: Transport separation (MCP ↔ microservice) — done

- **`BioImageService`** — protocol-neutral core extracted from
  `BioImageMcpServer`.  Owns the file-access policy (deny/allow/client
  roots), the reader/writer factories, the shared `--allow`/`--deny`
  CLI options, and the five image operations.  Each operation takes a
  flat snake_case `Map<String,Object>` (the lowest common denominator
  any transport can produce) and returns a `ToolResult`, converting
  argument-parse errors into `INVALID_ARGUMENT` failures instead of
  throwing.  The arg-parsing helpers (`parseRange`, `optInt`,
  `optEnum`, …) moved here so every adapter shares them.  Thread-safe:
  each call gets its own reader; policy held in `volatile` fields.
- **`BioImageMcpServer`** — now a thin MCP/stdio adapter over
  `BioImageService`.  Keeps only transport glue: JSON schemas, tool
  specs, the stdio transport, the client-roots → `setClientRoots`
  bridge, and `ToolResult → CallToolResult` mapping.  Public surface
  unchanged (`builder().allow()/.deny().build().run()`, `NAME`,
  `VERSION`) so the existing runner and tests are untouched.
- **`BioImageHttpService`** — sibling adapter proving separability.
  Plain `com.sun.net.httpserver` (no new deps).  `POST /<tool>` with
  the same JSON arg body; JSON tools return `application/json`, image
  tools return raw `image/png` bytes (thumbnail's projection in an
  `X-Projection-Used` header).  `ToolResult.Failure` → HTTP status by
  kind (403/400/504/502); malformed body → 400; wrong method → 405.
  Virtual-thread-per-task executor; each request gets its own reader.
  `JsonUtil.parseObject` added for request-body parsing.
- **`runner/bioimage_http.java`** — JBang wrapper mirroring
  `bioimage_mcp.java`.  Launching the microservice is just running a
  different wrapper file.  (Local dev runs the class from the fat jar
  directly until a release containing it is published, since the
  published `//DEPS` artifact would otherwise shadow new classes.)
- **Validated:** `mill test` 120 classes green; MCP smoke test 9/9
  (also fixed a stale `0.1.0` version assertion → `0.1.1`); HTTP
  adapter exercised live against the real Zeiss CZI fixture —
  inspect/plane/thumbnail and every error path (access-denied,
  invalid-arg, malformed-JSON, wrong-method, missing-file) confirmed.

### Possible future improvements (not blocking)

- **Concurrency hardening** — readers are created per-call (safe), but
  a multi-client microservice may want a bounded reader pool to cap
  open files / memory under load.


## Phase 10: Shared-memory deposit — done

Full protocol spec in DESIGN.md §9.

- **`BioImageService.deposit`** — new protocol-neutral operation that
  reads a selection (channel/Z/T ranges over a series) and writes the
  raw native pixel bytes contiguously into a client-owned region.  No
  normalization, no PNG — one layer below the display tools, looping
  `ImageReader.readPlane` and composing the volume itself (Bio-Formats
  has no multi-plane read).  Buffer is C-order, axis order
  `[t,z,c,y,x]`, X fastest.  Enforces `required ≤ capacity_bytes`
  (writes nothing on a short buffer), validates both source and target
  paths through the existing access policy, and never unlinks the
  region.  Returns a self-describing `DepositDescriptor`.  A
  `dry_run` flag returns the descriptor (with `total_bytes`) without
  writing, so the client can size the region first.  Runs under a
  `CancellableTask` so it honors both the timeout and a transport-
  driven `DepositHandle.cancel()`; the `PixelSink` is always closed.
- **`PixelSink` / `MappedFileSink`** — sink abstraction; the v1
  `file` implementation writes via positional `FileChannel.write`
  (long offsets, no 2 GB `MappedByteBuffer` limit, no FFM).  On
  `tmpfs`/`/dev/shm` the client's `mmap` sees the writes without a
  disk round-trip.  `posix_shm`/`win_named` kinds can slot in behind
  the same tagged `target` later.
- **`BioImageSocketService`** — third sibling adapter (UDS, NDJSON
  control plane).  Connection thread reads only (prompt EOF →
  cancel); deposit replies written from a worker so a disconnect is
  always noticed.  Sequential one-deposit-per-connection; connections
  reused.  Added a **`shutdown`** control message honored only when the
  requester is the sole connected client (tracked via an
  `AtomicInteger`), so one client can't tear the server out from under
  others; on `shutdown_ok` it closes the listener, removes the socket
  file, and exits.  `runner/bioimage_socket.java` wrapper.
- **Validated:** 5 `DepositTest` unit tests (byte-exact layout against
  `FakeImageReader`'s deterministic formula, multi-channel axis order,
  dry-run, capacity-too-small writes nothing, source-outside-allow
  denied) — 362 tests green.  Live UDS exercise against the real Zeiss
  CZI: hello, dry-run sizing, real fill into `/dev/shm` (688 128 bytes,
  data verified non-zero), capacity-rejection, connection reuse, abrupt
  mid-deposit disconnect (server survived and kept serving), shutdown
  refusal with 2 clients + clean self-shutdown as sole client (process
  exited, socket file removed — no kill needed).

### Possible future improvements (not blocking)

- **True shared memory fast-path** — POSIX `shm_open` / Windows named
  mapping via FFM, if `tmpfs` file indirection ever shows up in
  profiling.  Same protocol, new `PixelSink`.
- **Pipelined deposits** — the `id` field is already echoed, so
  relaxing the one-in-flight-per-connection rule is forward-compatible
  if a client wants concurrent fills into distinct regions.
- **Expose `deposit` on the HTTP adapter** — a `dry_run` over HTTP is a
  cheap way to size regions for clients that prefer request/response.


## Phase 11: Slice selections (Python-style ranges) — done

Replaced the old single/`_start`/`_end` integer selection params with a
unified **`Slice`** grammar across every tool, end to end.

- **`Slice`** — a comma-separated list of Python-style terms parsed from
  a string (a bare int is also accepted): `"9"`, `"-9"`, `"2:4"` (half-
  open), `"5:"`, `":-3"`, `":"`, and lists like `"0,2,5"` / `"4:9,11:"`.
  Resolves against a dimension size to an `int[]` (ordered, repeats
  allowed — `"3,3"` reads a plane twice on purpose). Half-open like
  Python; **explicit** out-of-bounds is an error (no silent clamp);
  empty selections error; step slices rejected. Also
  `resolveContiguous` → `Range` (for the OME-TIFF writer) and
  `resolveSingle` → int (for single-plane tools).
- **`Range`** promoted to a top-level resolved (inclusive) type; its old
  negative/inclusive `resolve` logic moved into `Slice`.
- **Missing = empty = error.** Omitting `channels`/`z`/`t` is now an
  error (you must write `":"` for all), so a call can never silently
  pull a whole dimension — fixes the deposit "grab everything" footgun.
  Single-plane selectors (`get_plane` channel/z/t, `get_thumbnail` t)
  keep their `0` default and now accept negative indices.
- **Wire params unified** to `channels`/`z`/`t` (slices) and
  `channel`/`z`/`t` (single) — replacing `channel`/`channel_start`/…,
  `z_slice`/`z_start`/…, `timepoint`/`t_start`/…, and the old `int[]
  channels`. Stats `:` on z/t still triggers adaptive reading; export
  requires z/t to be a single contiguous range (writer constraint),
  while channels may be any list.
- Updated MCP schemas, all five tools, `BioImageService`, `JsonUtil`
  (`StatsResult` now reports `channels`/`z_requested`/`t_requested` as
  int[]), the integration smoke test, and all tool tests; added
  `SliceTest`. Validated: 382 unit tests green, MCP smoke test 9/9, live
  socket deposit with `channels:"0,2"` (non-contiguous) + missing-
  selection error. **Breaking** API change; docs (service-endpoints.md,
  API-http.md, API-socket.md, DESIGN.md §9) updated.


## Phase 12: gRPC transport + stateful sessions — done

Full spec in DESIGN.md §10 (sessions) and §11 (gRPC).

- **Build conversion (staged, behavior-preserving first).**
  `build.mill.yaml` → Scala `build.mill`, done in two verified stages: a
  faithful 1:1 translation (no new deps) gated on `mill clean && compile
  && test` (382 green) + assembly + MCP smoke (9/9) — proving the migration
  changed nothing — then the gRPC layer.  YAML had to go because it can't
  express a custom codegen task.  `test/package.mill.yaml` folded into an
  inner `object test`.
- **Maven-fetched protoc toolchain.** A `protocGenerate` task resolves
  `com.google.protobuf:protoc` and `io.grpc:protoc-gen-grpc-java` as
  OS-classified `exe` artifacts via the Mill/coursier resolver
  (`;classifier=…;type=exe`, `artifactTypes=Some(Set(Type("exe")))`), copies
  them out of the read-only cache, `chmod +x`, runs protoc, and feeds the
  output into `generatedSources`.  No system protoc.  Versions pinned in
  lockstep: grpc-java 1.81.0 / protobuf 3.25.8 / protoc 3.25.8 (+
  `org.apache.tomcat:annotations-api` for the stubs' `javax.annotation`).
- **Session layer (`BioImageService`).** `openSession`/`closeSession` +
  a `withSession` seam; new `ImageSession` (open reader + canonical path +
  `ReentrantLock`, `AutoCloseable`), `HeldImageReader` (no-op open/close
  delegating wrapper so the existing tools reuse a shared reader unchanged),
  `SessionInfo` (handle + SUMMARY metadata).  All five read ops accept
  `handle` as an alternative to `path`; `runDeposit` refactored into a
  reusable `depositInto` with a handle branch holding the session lock.
  `export_to_tiff` stays path-only.  Sessions are owned by the connection;
  `closeSession` takes the lock so the reader is never closed mid-read.
- **Socket adapter extended.** Was deposit-only; now serves the read ops +
  `open`/`close` over NDJSON, accepting `handle` or `path`; JSON results
  under `result`, PNG as base64 (`png_base64`); per-connection handle set
  closed on disconnect.
- **gRPC adapter (`BioImageGrpcService`) + `src/proto/bioimage.proto` +
  `runner/bioimage_grpc.java`.** Single bidi `Session` stream = one
  connection; `oneof` client/server messages; loopback TCP via
  `grpc-netty-shaded`; deposit data still via the shared-memory region;
  stream death cancels in-flight deposit and closes all handles; `shutdown`
  honored only when sole stream.
- **Validated:** 390 → **392 unit tests green** (`SessionTest` ×6,
  `BioImageGrpcServiceTest` ×2 incl. dropped-stream-closes-reader,
  `SocketSessionTest` ×2 incl. disconnect cleanup); `mill clean` + full
  build + codegen clean; MCP smoke test 9/9 with the gRPC-laden assembly.
  Docs updated: DESIGN §10/§11, service-endpoints.md, API-socket.md, new
  API-grpc.md, CLAUDE.md (build now Scala), this file.

### Possible future improvements (not blocking)
- Secure/remote gRPC (TLS, UDS via native transport, authn).
- Idle-TTL session sweeper as a backstop beyond connection-scoped cleanup.
- Expose sessions on a future stateful HTTP variant if ever needed.


## Phase 13: NGFF-order deposit + portable metadata document — done

- **TCZYX deposit layout.** The shared-memory deposit now writes pixels in the
  NGFF / OME-Zarr canonical axis order `[t,c,z,y,x]` (was `[t,z,c,y,x]`), so a
  mapped region drops into napari/zarr/dask without a transpose and each
  channel's Z-stack is a contiguous (channel-major) block.  We normalize to
  this order regardless of the source file's arbitrary `dimensionOrder`.
  Changed: the `depositInto` loop nesting + offset, `DepositDescriptor`
  (AXIS_ORDER + javadoc), `DepositTest` (multichannel offset + rename), DESIGN
  §9.3/§9.4, API-socket.md §7 (formula, axis_order, NumPy shape), API-grpc.md.
  **Breaking** wire-layout change (descriptor's `axis_order` self-documents it).
- **`get_ome_metadata` operation.** Returns the file's full extended metadata as
  a portable, format-tagged `{format, content}` document — `ome_xml` (the
  universal case, synthesized by Bio-Formats) today, `ome_ngff` reserved for a
  reader that can supply a native OME-Zarr JSON block (Bio-Formats core can't —
  it needs the `OMEZarrReader` add-on and still normalizes through the OME
  model).  The tagged envelope means NGFF slots in with no wire change.  New
  `OmeMetadata` record + `ImageReader.getMetadataBlock()` default (derives the
  `ome_xml` block from `getOMEXML()`); `BioImageService.getOmeMetadata` with
  handle/path routing and a `max_response_bytes` cap (over-size → INVALID_ARGUMENT
  reporting the byte count; never truncated).  Exposed on all four transports:
  MCP tool (default 256 KB cap, since it enters context), HTTP `POST
  /get_ome_metadata`, socket `get_ome_metadata` op, gRPC
  `OmeMetadataRequest`/`OmeMetadataResult`.
- **Validated:** 393 unit tests green (+`SessionTest.omeMetadataByPathAndHandleAndCap`,
  plus get_ome_metadata round-trips folded into the gRPC and socket integration
  tests; DepositTest updated for TCZYX).  Docs updated: DESIGN §2.7 + §9,
  service-endpoints.md, API-socket.md, API-grpc.md, README, this file.


## Phase 14: Protocol governance, buf lint, deposit completeness — done

- **`mill bufLint`.** Standalone schema-lint command (not part of compile).
  The buf CLI is fetched from Maven Central (`build.buf:buf`) using the same
  OS-classified `exe` scheme as protoc, so `stageExe`/`protocClassifier` work
  verbatim — CLI-only, no Buf Schema Registry.  `buf.yaml` runs STANDARD,
  excepting the rules that conflict with deliberate choices (single bidi
  `Session` stream with `ClientMsg`/`ServerMsg` envelopes; flat `src/proto`).
  `buf breaking` deferred until v1 is frozen.
- **DESIGN §12 Protocol Governance.** Doctrine: socket/HTTP are sovereign
  surfaces; gRPC is a conformance surface (conform to an important client's
  contract rather than impose ours — cheap because the core is protocol-neutral,
  so it's another thin adapter).  N×M stability via one source of truth +
  protobuf wire-compat + versioned packages; semantic conformance (axis order,
  error kinds) lives in `service-endpoints.md`, not the `.proto`.
- **gRPC deposit concessions (§12.5).** `DepositRequest` gains `y`, `x`
  (accepted but IGNORED — full plane served) and `level` (only 0 served; any
  other REFUSED, never silently downgraded).  gRPC-only.  Safe because of the
  next item.
- **Per-axis selection in the descriptor.**  `DepositDescriptor` now reports
  `selection` — the resolved source indices delivered on **every** axis, in
  buffer order, as run-length `[start,stop)` ranges (`AxisSelection`/
  `IndexRange`).  Counts alone can't interpret an arbitrary/non-contiguous 5D
  subset (`channels:"0,2,5"` → `c:[[0,1],[2,3],[5,6]]`); the selection maps
  buffer index → source index on each axis.  X/Y are full here but reported
  anyway so the format is general.  Surfaced on socket (`filled.selection`) and
  gRPC (`Filled.selection` via new `AxisSelection`/`AxisRange` messages).
- **Validated:** 394 unit tests green (+`DepositTest` selection RLE,
  gRPC y/x-ignored + level-refused + selection assertions); buf lint green;
  MCP smoke 10/10.  Docs: DESIGN §9.4/§12, API-socket.md §5/§7, API-grpc.md.


## Phase 15: OME-NGFF (OME-Zarr v3 / NGFF 0.5) export — MCP path DONE; docs + other transports remain

**Status:** Steps 0–3 complete and validated end-to-end.  `export_to_ngff` is
live on the MCP/stdio transport.  408 unit tests green (+`ZarrWriterTest` ×7,
`ExportToNgffToolTest` ×7); MCP smoke 10/10 on the assembled jar; **live
real-CZI export** (Zeiss CZI → 3C×21Z OME-Zarr, 25 MB zstd) read back valid
with correct physical scales (Z 0.35 µm, XY 0.2048 µm).  Remaining: Step 4
docs (DESIGN §13, README, API-*.md) and the HTTP/socket/gRPC transports
(deliberately deferred — MCP-first was the chosen scope).

**Compression tuning exposed.** `export_to_ngff` takes both `codec`
(none/gzip/zstd/blosc) and `compression_level` (gzip 0–9, zstd −7…22 with
negative = fastest, blosc 0–9; not applicable to `none`; omitted = the
codec's default).  The level threads through as a `codec[:level]` spec to
`ZarrWriter`, which applies it (zstd keeps its checksum).  Out-of-range /
none+level are INVALID_ARGUMENT.  **Defaults:** when unspecified, codec is **zstd pinned at level 5**
(explicit, reported — a good speed/size balance), and **blosc is wired as
lz4 + byte-shuffle** (its fast identity; the earlier zstd-inner + noshuffle
default was strictly worse than plain zstd — no shuffle benefit on numeric
data).  gzip keeps its library default.  Verified on the
real CZI: zstd:5 ≈ 180 MB/s/core compression (isolated against a 277 ms
read+write baseline for a 43 MB timepoint); high zstd levels (19/22) cost ~7×
the time for no size gain; blosc (lz4+shuffle) gives near-free compression
(~245 ms ≈ the raw-write baseline, ratio ~1.56) — the speed pick, with zstd:5
the ratio pick (1.72).  Speed is usually the right priority for microscopy.



Add a second export target alongside OME-TIFF: **OME-Zarr** conforming to
**OME-NGFF 0.5** (which *is* Zarr v3).  Zarr's value is big data, so the
non-negotiable requirement is that export streams to disk with bounded
memory — which our existing plane-by-plane `ImageWriter` loop already does.

**Library decision (validated against Maven Central + bioformats2raw):**
- **`dev.zarr:zarr-java:0.1.3`** — the JVM-native Zarr v2/v3 library
  (zarr-developers).  On Maven Central (versions 0.1.0–0.1.3; the older
  0.0.x are not API-compatible).  Same library line bioformats2raw 0.12+
  uses, so it is the proven path for NGFF 0.5 output.  Region writes
  (`array.write(offset, ucar.ma2.Array)`), filesystem/S3/zip/memory stores,
  v3 sharding — exactly our streaming need.
- We write `zarr-java` directly behind a new `ImageWriter` impl, **not**
  shell out to bioformats2raw (a whole picocli app that would bypass our
  access-control, budget, and `ExportResult` machinery).

**Validated facts:** NGFF 0.5 = Zarr v3; a **single resolution level is
valid** (no pyramid required → MVP can skip downsampling).  Layout: a Zarr
group whose `zarr.json` carries `attributes.ome.multiscales` (axes t/c/z/y/x
with units, one dataset `"0"`, `coordinateTransformations` scale from physical
pixel sizes).  OME-XML is optional and, by the `bioformats2raw.layout`
convention, lives at `OME/METADATA.ome.xml`.

**Build risks to settle in Step 0 (the spike) — refine plan if any bite:**
- `edu.ucar:cdm-core:5.9.1` (netcdf-java; provides `ucar.ma2.Array`) is
  **NOT on Maven Central** → must add the **Unidata repo**
  (`https://artifacts.unidata.ucar.edu/repository/unidata-all/`) to
  `build.mill` `repositories` (same pattern as the OME repo already there).
- **protobuf clash:** netcdf cdm-core pulls its own protobuf-java; we pin
  3.25.8 for gRPC.  Confirm coursier resolves one compatible version and
  that gRPC codegen + all tests still pass.  This is the make-or-break check.
- **Footprint:** zarr-java drags in AWS `s3:2.34.6` (large), okhttp, netcdf,
  and native `blosc-java`/`zstd-jni`.  We only write local files, so we
  **exclude `software.amazon.awssdk:s3`** (S3 store unused).  RESOLVED: the
  assembly grew from ~? to **77 MB** — acceptable for the JBang download.
  Codec default is **zstd** (proven working incl. the native lib; far better
  ratio than gzip, which is the point of Zarr for big data); gzip stays
  available as the pure-Java fallback.
- Jackson 2.20.0 (zarr-java) vs Bio-Formats' Jackson 2.x — expect coursier to
  pick the higher 2.x; verify nothing breaks.

### Steps

- **Step 0 — Dependency spike (DE-RISK FIRST). — DONE.** Add `zarr-java:0.1.3` +
  Unidata repo to `build.mill`; exclude awssdk s3.  Resolve; confirm gRPC
  protobuf still 3.25.8 and `mill test` + codegen + MCP smoke stay green.
  Throwaway smoke test: create a v3 **sharded** array on local FS, write a
  region via `ucar.ma2.Array`, read it back, assert byte-exact.  Confirms the
  0.1.3 write API and that a chosen codec works.  **If the protobuf clash or
  footprint is unacceptable, stop and reconsider** (pin netcdf differently,
  exclude more, or fall back to a bioformats2raw subprocess).
- **Step 1 — `ImageWriter` refinement. — DONE.** The TIFF path uses an opaque
  sequential `planeIndex`; Zarr needs real `(c,z,t)` coordinates + the full
  shape/dimension order for the region offset.  Make plane writes
  coordinate-aware (carry c/z/t and the per-axis sizes), update
  `BioFormatsWriter` + `FakeImageWriter` + the export loop, and verify the
  OME-TIFF behavior is byte-for-byte unchanged.
- **Step 2 — `ZarrWriter implements ImageWriter`. — DONE.** `open()` parses
  dims/pixelType/physical sizes from the OME-XML and builds the NGFF 0.5
  `multiscales` metadata; creates the v3 array (chunking + sharding).
  `writePlane` decodes the raw native-order `byte[]` into a `ucar.ma2.Array`
  and writes at offset `[t,c,z,0,0]`.  Also writes the **OME-XML sidecar**
  (`OME/METADATA.ome.xml`) so nothing in the source metadata is silently lost
  (per project ethos) — and `ExportResult.warnings` flags anything not
  represented in the NGFF JSON itself.  New codec mapping (none/gzip/zstd/
  blosc), separate from the TIFF `Compression` enum.  Output path is a
  **directory** (the `.zarr` store): **refuse if it already exists** (never
  destroy user data).
- **Step 3 — Tool + service + MCP (decided: new tool, MCP-first). — DONE.** Add a
  **new `export_to_ngff` tool** (own Zarr-native params: codec
  none/gzip/zstd/blosc, chunk size — NOT the TIFF `Compression` enum) reusing
  the read/subset/budget plumbing.  Re-think the 2 GB `maxBytes` default —
  Zarr streams to disk with bounded memory, so the TIFF-era cap is the wrong
  knob.  Wire **MCP/stdio only for now** (schema + handler +
  `BioImageService.exportToNgff`); HTTP, socket, and gRPC (proto message →
  codegen + buf lint) are a deliberate follow-up.
- **Step 4 — Tests + docs.** Round-trip: `FakeImageReader` → OME-Zarr → read
  back with zarr-java → assert byte-exact against the deterministic pixel
  formula; multi-channel axis order; directory-exists refusal; each codec
  round-trips.  Docs: new DESIGN §13 + §2.x tool entry, README tool table,
  service-endpoints.md, API-*.md, and CLAUDE.md (new build repo/dep).

### Deferred to Phase 15b (not MVP)
- Multiscale **pyramid** generation (reuse the area-average downsampler from
  the thumbnail code) — needed for big data to be usable in napari/
  neuroglancer.
- S3 store target; reading `.zarr` back through our `ImageReader`
  (OMEZarrReader add-on or zarr-java directly).


## Phase 16: Sharded + parallel OME-Zarr export — DONE

Two coupled improvements to `export_to_ngff`: automatic sharding (so the
file count stays sane for volumetric/timeseries data) and a shared, capped
writer pool (so compression actually uses the cores).

- **Automatic sharding (no knobs).** Inner chunk is always **one plane**
  (ideal for plane-based microscopy reads — exactly one plane decompressed per
  access, cache-independent).  Sharding bundles plane-chunks into few files
  *without* changing read granularity.  Per series: plane > 1 MB → one
  plane/file; volume < 4 MB → whole volume in one shard; else shard = 2^m
  planes reaching ~1 MB.  Global 128k-file cap doubles shard depth (never past
  a whole volume) until under.  Nested v3 chunk keys kept (so multi-shard
  layouts are visible on disk).  `ZarrWriter.computeShardDepths`.
- **Block-buffered writes.** A shard is written in one `array.write` of a
  `[1,1,shardZ,Y,X]` block, so zarr-java never read-modify-writes a
  partially-filled shard.  New `ImageWriter.writeBlock` + `preferredBlockDepth`
  (defaults: split→writePlane, depth 1 — TIFF unchanged); `ZarrWriter`
  overrides both.
- **Shared, server-wide writer pool (`BlockPool`).** Owned by
  `BioImageService`, sized to `--parallelism`, shared by ALL clients/exports —
  total concurrent compression is capped regardless of client count.  A reader
  thread per export assembles whole shards and hands them off; a shared
  semaphore bounds total in-flight blocks (memory).  No per-export
  self-processing (it would break the hard total cap); a saturated reader
  blocks (correct backpressure).
- **`--parallelism`** (server-wide): integer = thread count, decimal =
  fraction of cores rounded up; default **0.334** (≈ cores/3 + 1).
  cgroup-aware via `availableProcessors()`.
- **Error isolation (no-crash + delete).** First write error cancels only that
  export's remaining work, drains its in-flight tasks, **deletes the partial
  store**, and fails only that operation — other clients keep running, JVM
  stays up.  Success is returned only after every block future completed
  without error (a dead worker can't masquerade as a finished export).  An
  "already exists" open refusal never deletes (the path is the user's).
- **Validated:** 427 unit tests (sharding-policy rules + cap; concurrent
  disjoint-*block* writes; forced-write-error fails op + deletes store;
  `--parallelism` resolver + wiring); MCP smoke 10/10.  Live real-CZI:
  parallelism scales ~1→2→4→8 = 3381→1717→1244→993 ms (byte-identical
  output), sharding 63 plane-files → 37.


## Already done

- Project skeleton, Mill build, JUnit 5 tests
- `PathAccessControl` — file access security (deny > allow > client roots)
- `CancellableTask` — virtual-thread work runner with timeout and
  interrupt-with-backoff, plus `Handle` for early cancellation
- **Phase 1a: Model records** — `PixelType`, `PixelSize` (BigDecimal-backed,
  exact unit conversion), `ChannelInfo`, `InstrumentInfo`, `PlaneCoordinate`,
  `SeriesInfo`, `ImageMetadata` (with `SeriesSummary` and `DetailLevel`),
  `IntensityStats`.  All in `src/server/`, tested.
- **Phase 1b: Reader abstraction** — `ImageReader` interface with 5 methods:
  `open`/`close` lifecycle, `getSeriesCount`, `getMetadata(series, detailLevel)`,
  `isLittleEndian(series)`, `readPlane(series, channel, z, timepoint)`.
  Returns raw `byte[]` in row-major order; tools get dimensions and pixel type
  from `SeriesInfo` via `getMetadata`.
- **Phase 1c: Fake reader** — `FakeImageReader` (in `test/`) with builder
  pattern and `FakeSeries` record.  Deterministic pixel formula
  (`y*sizeX + x + c*7 + z*13 + t*31` mod type range) lets tests compute
  expected values independently.  Detail-level filtering, configurable byte
  order, coordinate validation.  22 tests.
- **Tool infrastructure** — `ToolResult<T>` sealed interface (Success/Failure
  with ErrorKind enum: ACCESS_DENIED, INVALID_ARGUMENT, IO_ERROR, TIMEOUT).
  `PathValidator` functional interface wrapping access checks.  Tools return
  structured results, never throw — errors are first-class outcomes.
- **Phase 2a: `inspect_image`** — `InspectImageTool` returns
  `ToolResult<ImageMetadata>`.  Takes path + PathValidator + reader factory +
  budget (timeout, maxResponseBytes).  Validates path, opens reader inside
  CancellableTask, gets metadata, caps response size (truncates extraMetadata
  first, then downgrades detail level).  15 tests.
- **Phase 2b: `get_plane`** — `GetPlaneTool` returns `ToolResult<byte[]>`
  (PNG).  `PixelConverter` utility handles byte→double extraction for all 9
  pixel types with correct signedness/byte order, plus uint8 mapping
  (auto-contrast via percentile stretch or full-range normalization).
  Area-average downsampling.  15 tool tests + 20 converter tests.
- **Phase 2c: `get_intensity_stats`** — `StatsAccumulator` (sealed class,
  two implementations) accumulates stats across multiple planes without
  converting everything to double arrays.  `ExactAccumulator` for 8/16-bit
  types uses counting arrays for exact percentiles and histograms.
  `DigestAccumulator` for 32-bit/float/double uses t-digest (`com.tdunning:
  t-digest:3.3`) for streaming percentile estimation, with histogram derived
  from the digest CDF at finish time.  `GetIntensityStatsTool` orchestrates
  reading planes with two modes: **explicit** (user specifies ranges, even
  subsampling if over byte budget) and **adaptive** (null ranges, reads
  incrementally and stops when 90th-percentile time estimate or byte budget
  would be exceeded).  Adaptive mode uses `ReadRateEstimator` backed by
  Commons Math `SimpleRegression` + t-distribution prediction intervals;
  requires ≥2 observations before trusting estimates.  In volume mode
  (Z>1 and T>1), steps by full Z-stacks to ensure at least one complete
  volume per channel.  Supports `Range` parameters for channel/Z/T
  selection; `StatsResult` wrapper includes resolved ranges and actual
  indices used.  33 accumulator tests + 30 tool tests + 7 estimator tests.
- **Phase 2d: `get_thumbnail`** — `GetThumbnailTool` returns
  `ToolResult<byte[]>` (RGB PNG).  Z-projection via `Projection` enum
  (MID_SLICE, MAX_INTENSITY, SUM): mid-slice reads only the middle plane,
  max/sum iterate all Z-slices accumulating per-pixel.  Multi-channel
  compositing: each channel is auto-contrasted independently (percentile
  stretch), then additively blended using per-channel colors.  Colors
  come from `ChannelInfo.color()` (ARGB from OME metadata) with sensible
  defaults (green for 1-ch, cyan/magenta for 2-ch, cyan/magenta/yellow
  for 3-ch, rotating palette beyond that).  Area-average RGB downsampling
  to fit `maxSize`.  Budget via timeout + maxBytes (total across all
  channels × Z-slices).  28 tests covering projections, compositing,
  color defaults, metadata colors, downsampling, uint16, and error cases.
- **Phase 2e: `export_to_tiff`** — `ExportToTiffTool` reads from
  `ImageReader`, writes to new `ImageWriter` interface.  OME-XML
  pass-through architecture: reader provides raw OME-XML via new
  `getOMEXML()` method; `OmeXmlSurgery` (DOM-based) modifies it for
  subsetting (updates SizeC/Z/T, removes Channel/Plane/TiffData elements,
  rebuilds TiffData for the subset).  Three metadata modes:
  `ALL` (full XML including OriginalMetadataAnnotations),
  `STRUCTURED` (strip OriginalMetadata, keep schema elements),
  `MINIMAL` (Pixels/Channel/TiffData only).  Compression enum
  (NONE/LZW/ZLIB).  `ExportResult` reports dimensions written,
  metadata preservation counts, and warnings about: proprietary format
  conversion, OriginalMetadata stripping, flat metadata not in XML.
  `FakeImageReader` generates synthetic OME-XML with configurable
  OriginalMetadataAnnotations (uses commons-text for XML escaping).
  `FakeImageWriter` captures all writes for assertions.  Also added
  `getOriginalMetadataCount()` to `ImageReader` for detecting flat
  metadata not serialized to OME-XML.  13 XML surgery tests +
  26 tool tests.


## Phase 3: Budget and resource constraints — done

- **`get_thumbnail` adaptive projection** — New `Projection.ADAPTIVE`
  (now the default).  Reads the central Z-slice for all channels first
  (calibration + fallback), then incrementally accumulates a max-intensity
  projection, checking `ReadRateEstimator` time budget and byte budget
  before each Z-slice batch.  If a budget limit is approached, falls back
  to the clean mid-slice backup — no partial max, no failure.  Returns
  `ThumbnailResult` record with `projectionUsed` (always a concrete
  mode, never ADAPTIVE).  Explicit projection modes (MID_SLICE,
  MAX_INTENSITY, SUM) retain hard-fail behavior on budget exceeded.
  36 thumbnail tests + 14 MaxProjection tests.
- **`MaxProjection` sealed interface** — Type-specialized max-intensity
  accumulator.  `IntMaxProjection` (BIT/UINT8/INT8/UINT16/INT16) uses
  `int[]` working arrays with per-type decode loops and counting-sort
  histogram for O(n) percentile calculation — no double[] intermediates,
  no O(n log n) sort.  `DoubleMaxProjection` (INT32/UINT32/FLOAT/DOUBLE)
  uses `double[]` with sort-based percentiles.  `fork()` for snapshotting
  mid-slice state.  Histogram percentile uses the same linear-interpolation
  formula as `PixelConverter.percentile`, so int and double paths produce
  identical output for integer data (verified by tests).
- Other tools: `inspect_image` already degrades detail level gracefully;
  `get_intensity_stats` has adaptive reading with rate estimation;
  `get_plane` and `export_to_tiff` use appropriate hard-fail behavior
  (single-plane and all-or-nothing respectively).


## Phase 4: Bio-Formats reader/writer implementation — done

- **`BioFormatsReader`** — `ImageReader` backed by Bio-Formats
  `formats-gpl`.  Wraps `loci.formats.ImageReader` with
  `OMEXMLService` for structured metadata extraction.  Maps
  Bio-Formats pixel types, physical sizes (with unit conversion via
  `ome.units`), channel info (wavelengths, colors, fluor),
  instrument/objective metadata (with ID-based lookup through
  instrument refs and objective settings), and acquisition dates to
  our model records.  Detail-level filtering matches `FakeImageReader`
  behavior.  OME-XML pass-through via `getOMEXML()` with original
  metadata population.  `getOriginalMetadataCount()` aggregates
  global + per-series flat metadata entries.  Defensive `safeGet()`
  helper for Bio-Formats methods that sometimes throw instead of
  returning null.
- **`BioFormatsWriter`** — `ImageWriter` backed by Bio-Formats
  `OMETiffWriter`.  Always uses BigTIFF.  Supports Uncompressed, LZW,
  and zlib compression.  `getBytesWritten()` queries actual file size.
- **`BioFormatsReaderTest`** — 28 round-trip integration tests.
  Creates synthetic OME-TIFF files with `BioFormatsWriter`, reads
  back with `BioFormatsReader`.  Covers: lifecycle (open/close, double
  close, missing file); metadata (dimensions, format name, series
  name, channel names, dimension order, all three detail levels,
  multi-series summaries, physical pixel sizes); pixel data (uint8,
  uint16, multi-channel, multi-Z/T); byte order; OME-XML retrieval;
  LZW compression round-trip; pre-open state checks.
- **`BioFormatsProprietaryTest`** — 10 tests against a real Zeiss CZI
  file downloaded from the OME sample data repository (IDR collection).
  `TestFixtures` helper auto-downloads and caches fixtures in
  `test/fixtures/` (gitignored); tests skip via `assumeTrue` if the
  download fails (no network, server unavailable).  Covers: open,
  format name detection, positive dimensions, pixel data readability
  (correct byte count), standard metadata (channels, dimension order),
  full vs standard extra metadata, OME-XML generation, original
  metadata count, all-series summaries, byte order.  Confirms
  Bio-Formats reads a real proprietary format end-to-end through our
  abstraction layer.
- Note: OME-XML Channel elements require `SamplesPerPixel` attribute
  for the writer — discovered during testing.

### Possible future improvements (not blocking)

- Additional proprietary format fixtures (ND2, LIF, etc.) via the
  same `TestFixtures` mechanism — just add more `FixtureDef` entries.
- Instrument/objective metadata round-trip test (requires OME-XML
  with Instrument/Objective/ObjectiveSettings elements — not yet
  tested because the writer needs well-formed instrument references
  in the input XML).
- Side-by-side comparison of `FakeImageReader` vs `BioFormatsReader`
  on the same file to verify the fake's contract closely matches the
  real implementation.


## Phase 5: MCP server wiring — done

- **`BioImageMcpServer`** — complete rewrite.  Builder pattern for
  allow/deny path configuration (as envisioned in DESIGN.md §5.3).
  `run()` method creates `StdioServerTransportProvider` with captured
  stdout (System.out redirected to stderr to prevent Bio-Formats
  logging from corrupting the JSON-RPC stream).  Registers all 5 tools
  via `McpServer.sync(transport)` builder.  Client roots handled via
  `rootsChangeHandler` callback — extracts `file://` URIs from
  `McpSchema.Root` and rebuilds `PathAccessControl` dynamically.
- **Tool JSON schemas** — `McpSchema.JsonSchema` for each tool with
  descriptive parameter docs, enum constraints, and sensible defaults.
  Flat snake_case parameters for LLM-friendliness.
- **Argument parsing** — `Map<String, Object>` → tool `Request` records
  with type-safe helpers: `requireString`, `optInt`, `optLong`,
  `optBool`, `optDuration`, `optIntArray`, `optEnum`.
- **Result mapping** — `ToolResult.Success` → `CallToolResult` with
  appropriate content types; `ToolResult.Failure` → `isError(true)`.
  Image tools (`get_plane`, `get_thumbnail`) return `ImageContent`
  (base64 PNG) + optional `TextContent` metadata.  Text tools
  (`inspect_image`, `get_intensity_stats`, `export_to_tiff`) return
  `TextContent` with JSON.
- **`JsonUtil`** — domain record serialization via Jackson 3.x
  (already on classpath from MCP SDK, `tools.jackson.databind`
  package — no conflict with Jackson 2.x from Bio-Formats).
  Each domain type has a `toMap` method producing
  `Map<String, Object>` with clean LLM-friendly keys; Jackson
  `ObjectWriter` handles final JSON serialization with indentation.
  12 tests covering all result types, null omission, escaping.
- **Output path validation** — `ExportToTiffTool.execute` now accepts
  separate `PathValidator` instances for input and output paths (with
  a convenience overload for the single-validator case).  The server
  uses an `outputPathValidator()` that checks the parent directory
  rather than the file itself, since the output file doesn't exist yet.
- **Tool annotations** — all read-only tools marked with
  `readOnlyHint: true`; `export_to_tiff` creates files so
  `readOnlyHint: false`.
- 12 new `JsonUtilTest` tests + 13 new `BioImageMcpServerTest` tests
  (builder, defaults, end-to-end tool execution with `FakeImageReader`,
  error handling).  Total: 365 tests passing.

### Not yet done (deferred)

- **Progress notifications** — requires threading integration with
  `CancellableTask` to send `ProgressNotification` during long
  operations.  The `McpSyncServerExchange.progressNotification()`
  method is available but not yet wired.


## Phase 6: Runner and end-to-end

- **JBang runner** — `runner/bioimage-mcp.java` with builder pattern
  for allow/deny path configuration (commented-out examples).
  Uses `//DEPS` with Maven coordinates for published releases;
  developers use `mill run` or `jbang --cp $(mill show assembly)`.
- **Integration smoke test** — `integration-test/SmokeTest.java`,
  a standalone JBang script (not part of `mill test`).  Spawns the
  server as a subprocess via `jbang --quiet --cp <assembly.jar>`,
  exercises the full MCP protocol over stdio: initialize handshake,
  tools/list (verifies all 5 tools with schemas), and tool calls
  for all 5 tools against a synthetic OME-TIFF fixture (also
  created via a JBang helper script).  9 checks.
  Run with: `mill assembly && jbang integration-test/SmokeTest.java`
- **Build fix** — disabled Mill's `prependShellScript` on the
  assembly jar.  The prepended shell/batch launcher makes the jar
  unreadable by `javac` on JDK < 25 (`ZipException` — the zip
  reader in older `javac` doesn't tolerate prefix bytes, though
  the JVM runtime classloader does).  This broke JBang's `--cp`
  flag since JBang compiles against the jar.  To re-enable the
  executable jar feature: set `prependShellScript: default` in
  `build.mill.yaml`.

### Done

- **LLM-friendly instructions** — `SERVER_INSTRUCTIONS` constant with
  recommended workflow (inspect → thumbnail → plane/stats → export),
  key points (absolute paths, zero-based indices, error kinds).
  Per-tool descriptions rewritten for LLM consumption: each explains
  what to use the tool for, not just what it does.
- **Tested with Claude Code** — all 5 tools exercised against a real
  Zeiss CZI file (Plate1-Blue-A-02-Scene-1-P2-E1-01.czi).  Thumbnail
  and single-plane PNG images verified visually — correct channel
  compositing and auto-contrast.  Error messages verified (ACCESS_DENIED
  for out-of-scope paths, clear message for nonexistent files).
- **README** — deployment instructions (Claude Code, Claude Desktop),
  tool surface, build/dev workflow, project structure.

### Still to do

- Progress notifications (deferred from Phase 5)
- Test with Claude Desktop


## Phase 7: Publishing to Maven Central

- Maven coordinates decided: `com.github.ichoran:bioimage_mcp_server`.
- Publishing configuration added to `build.mill.yaml`: `PublishModule`,
  `publishVersion`, `pomSettings` (description, organization, URL,
  license, SCM, developer info).  `mill publishLocal` verified.
- JBang runner updated with correct `//DEPS` and `//REPOS` for OME
  Maven repository.

### Still to do

- Set environment variables for Sonatype Central credentials and GPG
  key (`MILL_SONATYPE_USERNAME`, `MILL_SONATYPE_PASSWORD`,
  `MILL_PGP_SECRET_BASE64`, `MILL_PGP_PASSPHRASE`).
- Publish: `mill mill.javalib.SonatypeCentralPublishModule/`
- Tag release `v0.1.0`.
- Verify the runner works with the published artifact:
  `jbang runner/bioimage-mcp.java` with no local build.


## Phase 8: v0.1.1 improvements — done

- **CLI path arguments** — `--allow <path>` and `--deny <path>` flags
  parsed via Apache Commons CLI (`commons-cli:commons-cli:1.11.0`).
  CLI paths are merged with builder-configured paths; the runner
  forwards `args` to `run()`.  Primary recommendation remains editing
  the runner file for inspectability; CLI flags are for one-off use.
- **`get_intensity_stats` range parameters** — schema now exposes
  `channel_start`/`channel_end`, `z_start`/`z_end`, `t_start`/`t_end`
  alongside the existing single-value shortcuts (`channel`, `z_slice`,
  `timepoint`).  Using both forms for the same dimension is an error.
  Negative indices count from the end (`-1` = last, `-2` = second-to-last,
  etc.).  `Range.resolve(size, name)` converts negative indices to
  absolute positions and validates bounds; out-of-range after resolution
  is still an error.  Omitting `_end` defaults to `-1` (last); omitting
  `_start` defaults to `0`.
- **Version bump** — `0.1.0` → `0.1.1` in build, server, and runner.
- 367 tests passing (net +2 from expanded Range tests).

### Still to do

- Publish `0.1.1` to Maven Central.
- Tag release `v0.1.1`.
