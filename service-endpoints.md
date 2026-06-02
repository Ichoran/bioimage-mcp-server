# BioImage service — common reference

All BioImage transports wrap one protocol-neutral core
(`BioImageService`).  This document describes what is **common** to every
transport: the operation catalog, argument conventions, the error model,
access control, and pixel-data conventions.  The transport-specific wire
formats are documented separately:

- **[API-http.md](API-http.md)** — the plain-HTTP microservice
  (`BioImageHttpService`, runner `bioimage_http.java`).
- **[API-socket.md](API-socket.md)** — the Unix-domain-socket
  session + shared-memory deposit service (`BioImageSocketService`, runner
  `bioimage_socket.java`).
- **[API-grpc.md](API-grpc.md)** — the local gRPC session service
  (`BioImageGrpcService`, runner `bioimage_grpc.java`).
- **MCP** — the stdio MCP server (`BioImageMcpServer`, runner
  `bioimage_mcp.java`) is self-describing: an MCP client calls
  `tools/list` to discover the tools and their JSON schemas.  See the
  top-level `README.md` for how to connect one; it is not re-documented
  here.

## 1. Which transport exposes which operations

The core operations are the same; each transport exposes a subset.

| Operation             | Returns                    | MCP | HTTP | Socket | gRPC |
|-----------------------|----------------------------|:---:|:----:|:------:|:----:|
| `inspect_image`       | metadata (JSON)            |  ✓  |  ✓   |   ✓    |  ✓   |
| `get_thumbnail`       | RGB PNG                    |  ✓  |  ✓   |   ✓    |  ✓   |
| `get_plane`           | grayscale PNG              |  ✓  |  ✓   |   ✓    |  ✓   |
| `get_intensity_stats` | statistics (JSON)          |  ✓  |  ✓   |   ✓    |  ✓   |
| `export_to_tiff`      | result (JSON); writes file |  ✓  |  ✓   |        |      |
| `deposit`             | region descriptor          |     |      |   ✓    |  ✓   |
| `open` / `close`      | session handle             |     |      |   ✓    |  ✓   |

**Sessions (socket + gRPC only).** The two persistent-connection transports
let a client `open` an image once — receiving a **handle** plus a SUMMARY
metadata snapshot — and then address subsequent read operations
(`inspect_image`, `get_plane`, `get_intensity_stats`, `get_thumbnail`,
`deposit`) by that handle (in place of `path`), reusing one already-open
reader instead of re-opening and re-parsing the file each call.  A session is
owned by the connection that opened it: when the client sends `close`, or the
connection drops, the kept-open reader is closed.  See §3.7 and the
per-transport docs.  MCP and HTTP remain **stateless** (path per call); HTTP
has no persistent connection to own a session lifetime, and the stdio MCP
server is single-client/sequential.

## 2. Argument conventions

Every operation takes a flat object of **snake_case** parameters.  The
same names are used on every transport (an MCP tool-call `arguments`
object, an HTTP JSON request body, or a socket `deposit` message).

- **Absolute paths.** All file paths must be absolute.
- **Zero-based indices.** `series`, `channel`, `z`, `t` are all zero-based.
- **Slice selections.** Operations that read across a dimension take a
  **slice** for `channels`, `z`, and `t` — a string in Python-slice
  syntax (a bare integer is also accepted for a single index):

  | Input       | Meaning (N = dim size)         | Indices |
  |-------------|--------------------------------|---------|
  | `"9"` / `9` | single index                   | {9} |
  | `"-9"`      | 9th from the end               | {N−9} |
  | `"2:4"`     | half-open `[2,4)`              | {2,3} |
  | `"5:"`      | `[5,N)`                        | {5…N−1} |
  | `":-3"`     | `[0,N−3)`                       | all but last 3 |
  | `":"`       | `[0,N)`                        | all |
  | `"0,2,5"`   | a list of indices              | {0,2,5} |
  | `"4:9,11:"` | a list of ranges               | {4…8, 11…N−1} |

  - **Half-open**, like Python: `"2:4"` is two indices, not three.
  - **Negative endpoints count from the end** (`-1` = last).
  - **Lists are concatenated in order and may repeat** an index (e.g.
    `"3,3"`) — if you ask to read a plane twice, that is honored.
  - **Explicit bounds past the end are an error**, never silently
    clamped. Open ends (`"5:"`, `":"`) clamp to the dimension size.
  - **An empty selection is an error**, and so is **omitting** a
    required slice: you must write `":"` to mean "all", so a dimension
    is never silently selected in full. (This is what stops a naive call
    from accidentally pulling an entire time-series.)
  - **Single-index selectors** (`get_plane`'s `channel`/`z`/`t`,
    `get_thumbnail`'s `t`) take one index, which may be negative; a slice
    that resolves to more than one index is an error there.
- **Budgets.** Operations that read pixel data accept:
  - `timeout_seconds` (integer) — wall-clock limit; on expiry the
    operation returns a `TIMEOUT` error (or, for adaptive reads, a
    documented partial result).
  - `max_bytes` (integer) — approximate cap on raw pixel bytes read.
    (`inspect_image` uses `max_response_bytes` instead, capping the
    metadata response size.)

## 3. Operation catalog

Parameters marked **required** must be present; all others are optional
with the stated default.

### 3.1 `inspect_image` → metadata JSON

| Param                | Type   | Default    | Notes |
|----------------------|--------|------------|-------|
| `path`               | string | —          | **required** |
| `series`             | int    | `0`        | |
| `detail`             | enum   | `standard` | `summary` \| `standard` \| `full` |
| `timeout_seconds`    | int    | `30`       | |
| `max_response_bytes` | int    | `65536`    | over this, detail level is downgraded |

### 3.2 `get_thumbnail` → RGB PNG

| Param             | Type     | Default    | Notes |
|-------------------|----------|------------|-------|
| `path`            | string   | —          | **required** |
| `series`          | int      | `0`        | |
| `projection`      | enum     | `adaptive` | `mid_slice` \| `max_intensity` \| `sum` \| `adaptive` |
| `channels`        | slice    | —          | **required** — which channels to composite (`":"` for all) |
| `t`               | int      | `0`        | single timepoint (negative ok) |
| `max_size`        | int      | `1024`     | max output dimension (px) |
| `timeout_seconds` | int      | `60`       | |
| `max_bytes`       | int      | `536870912`| 512 MB |

### 3.3 `get_plane` → grayscale PNG

| Param             | Type   | Default | Notes |
|-------------------|--------|---------|-------|
| `path`            | string | —       | **required** |
| `series`          | int    | `0`     | |
| `channel`         | int    | `0`     | single index (negative ok) |
| `z`               | int    | `0`     | single index (negative ok) |
| `t`               | int    | `0`     | single index (negative ok) |
| `normalize`       | bool   | `true`  | `true` = percentile auto-contrast; `false` = full-range |
| `max_size`        | int    | none    | downsample so the largest dimension fits |
| `timeout_seconds` | int    | `30`    | |
| `max_bytes`       | int    | `268435456` | 256 MB |

### 3.4 `get_intensity_stats` → statistics JSON

| Param                        | Type | Default     | Notes |
|------------------------------|------|-------------|-------|
| `path`                       | string | —         | **required** |
| `series`                     | int  | `0`         | |
| `channels`                   | slice | —          | **required** (`":"` = all) |
| `z`                          | slice | —          | **required**; `":"` reads all Z adaptively within budget |
| `t`                          | slice | —          | **required**; `":"` reads all T adaptively within budget |
| `histogram_bins`             | int  | `256`       | |
| `timeout_seconds`            | int  | `60`        | |
| `max_bytes`                  | int  | `536870912` | 512 MB |

### 3.5 `export_to_tiff` → result JSON (**writes a file**)

| Param             | Type   | Default | Notes |
|-------------------|--------|---------|-------|
| `path`            | string | —       | **required** source |
| `output_path`     | string | —       | **required** destination `.ome.tif(f)` |
| `series`          | int    | all     | |
| `channels`        | slice  | —       | **required** (`":"` = all); any list |
| `z`               | slice  | —       | **required**; must be a single contiguous range |
| `t`               | slice  | —       | **required**; must be a single contiguous range |
| `compression`     | enum   | `none`  | `none` \| `lzw` \| `zlib` |
| `metadata_mode`   | enum   | `all`   | `all` \| `structured` \| `minimal` |
| `timeout_seconds` | int    | `300`   | |
| `max_bytes`       | int    | `2147483648` | 2 GB |

`output_path` is validated against the access policy by its **parent
directory** (the file does not yet exist).  This is the only operation
that creates a file; it never modifies the source.

### 3.6 `deposit` → region descriptor (socket only)

Reads a selection and writes raw pixels into a client-owned region.  Full
details in **[API-socket.md](API-socket.md)**.

| Param             | Type   | Default | Notes |
|-------------------|--------|---------|-------|
| `path`            | string | —       | **required** source (or use `handle`) |
| `handle`          | string | —       | session handle; alternative to `path` (socket/gRPC) |
| `series`          | int    | `0`     | |
| `channels`        | slice  | —       | **required** (`":"` = all); any list |
| `z`               | slice  | —       | **required** (`":"` = all); any list |
| `t`               | slice  | —       | **required** (`":"` = all); any list |
| `dry_run`         | bool   | `false` | report size/layout without writing |
| `target`          | object | —       | required unless `dry_run`; see API-socket.md |
| `timeout_seconds` | int    | `60`    | |

> Note: `channels`, `z`, and `t` are **required** — there is no implicit
> "all". To deposit a whole volume write `"z": ":"`, etc.; to deposit one
> plane write single indices. This is deliberate, so a deposit can never
> silently pull the entire dataset.

### 3.7 `open` / `close` → session handle (socket + gRPC)

`open` keeps a reader open and returns a `handle` plus a SUMMARY metadata
snapshot; `close` releases it.  Any of the read operations above
(`inspect_image`, `get_plane`, `get_intensity_stats`, `get_thumbnail`,
`deposit`) may then pass `handle` **instead of** `path` to run against the
already-open reader.  Operations on one handle are serialized (Bio-Formats
readers are not thread-safe).

| Param             | Type   | Default | Notes |
|-------------------|--------|---------|-------|
| `path`            | string | —       | **required** for `open` — the image to open |
| `series`          | int    | `0`     | `open`: series whose summary is returned |
| `timeout_seconds` | int    | `30`    | `open`: budget for parsing metadata |
| `handle`          | string | —       | **required** for `close` |

A handle is valid only on the connection that opened it and only until that
connection closes it or drops; there is no cross-connection sharing.  See
**[API-socket.md](API-socket.md)** / **[API-grpc.md](API-grpc.md)** for the
exact messages.

## 4. Error model

Every operation either succeeds or fails with a structured error.  Errors
are never silently swallowed.  The machine-readable `kind` is one of:

| Kind               | Meaning |
|--------------------|---------|
| `ACCESS_DENIED`    | path not permitted by the access policy, or unresolvable |
| `INVALID_ARGUMENT` | a parameter is missing, malformed, or out of range |
| `IO_ERROR`         | file not found / unreadable / corrupt, or a write failed |
| `TIMEOUT`          | the operation exceeded its `timeout_seconds` budget |

Each transport maps these onto its own conventions (HTTP status codes,
socket `error` messages); see the per-transport docs.  In every case the
error carries a human-readable `message`.

## 5. Access control

The server only touches paths permitted by a three-tier policy
(see DESIGN.md §5):

1. **Deny list** — always blocked (highest precedence).
2. **Allow list** — explicitly permitted server-side.
3. **Client roots** — paths an MCP client declares in scope (MCP only).

A path is accessible iff it is **not** under any deny entry **and** is
under an allow entry or a client root.  Symlinks and `..` are resolved
before checking.

Allow/deny paths are configured by editing the runner file
(`.allow(...)` / `.deny(...)`) or via repeatable CLI flags `--allow
<path>` / `--deny <path>`, merged together.  The HTTP, socket, and gRPC
transports have no client roots, so they rely entirely on allow/deny —
a request to a path outside the allow list is rejected with
`ACCESS_DENIED`.  A session `handle` does not bypass this: the path was
already checked at `open` time, and the (still-stable) allow/deny policy is
re-applied on every handle operation.

## 6. Pixel-data conventions

These apply wherever raw pixel bytes are exposed (notably `deposit`):

- **Pixel types:** `int8`, `uint8`, `int16`, `uint16`, `int32`,
  `uint32`, `float` (32-bit IEEE), `double` (64-bit IEEE), `bit`.
  `bytes_per_sample` is 1/1/2/2/4/4/4/8/1 respectively (`bit` is
  delivered unpacked as one byte per sample).
- **Byte order:** multi-byte samples use the source file's native byte
  order.  It is reported (e.g. `little_endian`) rather than forced — the
  client adapts.
- **Plane order:** a single 2D plane is row-major (X varies fastest),
  `sizeX * sizeY * bytes_per_sample` bytes.
