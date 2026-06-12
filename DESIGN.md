# BioImage MCP Server — Design Document

_Authors: Rex A. Kerr, Claude Opus 4.6_

## 1. Project Vision

An MCP (Model Context Protocol) server that enables LLMs to read, inspect, and
work with microscopy image data.  Microscopy file formats are notoriously opaque
— proprietary headers, scattered metadata, format-specific tooling requirements —
and scientists routinely need answers to basic questions ("what are the
dimensions?", "what channels were acquired?", "what's the pixel size?") that
currently require launching heavyweight desktop applications or writing one-off
scripts.

This server makes microscopy data conversationally accessible.  A researcher can
hand Claude a `.czi`, `.nd2`, `.lif`, or `.ome.tiff` file and immediately ask
questions about it, preview it, extract statistics, or convert it to an open
format — without leaving the chat.

### 1.1 Design Philosophy

**Start with I/O and metadata; compute comes later.**  Every advanced capability
(segmentation, colocalization, deconvolution, stitching) begins by reading the
image and understanding its structure.  The POC focuses on building a solid
foundation for reading microscopy files and exposing their contents in a
structured, LLM-friendly way.

**Genuinely useful on day one.**  This is not a toy demo.  Metadata extraction
and format conversion alone solve real pain points for working microscopists.

**Low barrier to entry.**  A user with JBang installed can be running the server
in under a minute, with no repository clone, no build step, and no dependency
management.

**Never delete a user's primary image data.**  The server is a *read and export*
tool.  No operation — conversion, export, cleanup, or any future capability —
should ever delete, overwrite, or modify a user's original image files.  Source
data is sacred.  If a workflow produces derived files, those are new files; the
originals remain untouched.

**Extensible by design.**  The architecture anticipates future computational
tools (Python-based ML models, batch processing, OMERO integration) without
over-engineering for them now.


## 2. Proof-of-Concept Tool Surface

The POC exposes five MCP tools.  Together they cover the fundamental workflow of
"what is this file, what does it look like, is the data reasonable, and can I get
it into an open format?"

### 2.1 `inspect_image`

**Purpose:** Read a microscopy file and return structured metadata.

**Input:**
- `path` (string, required) — absolute path to the image file.
- `series` (integer, optional, default 0) — which image series to inspect, for
  multi-series formats.
- `detail` (enum: `summary` | `standard` | `full`, optional, default `standard`)
  — how much metadata to return (see below).

**Output (JSON):**
- Format name and version
- Number of series in the file
- For the selected series:
  - Dimension order and sizes (X, Y, Z, C, T)
  - Pixel type (uint8, uint16, float32, etc.)
  - Physical pixel sizes with units (µm, nm, etc.)
  - Channel metadata (names, excitation/emission wavelengths, colors)
  - Instrument metadata (objective, magnification, NA, immersion)
  - Acquisition timestamps (per-plane if available, or global)
  - Any additional key-value metadata the format exposes
- `omitted_metadata_bytes` — when `detail` is not `full`, the approximate size
  in bytes of metadata that was available but not returned.

**Detail levels:**

- **`summary`:** Dimensions, pixel type, physical pixel sizes, channel count and
  names.  Enough for the LLM to understand what the file contains and ask
  follow-up questions.  Fast and small even for files with enormous metadata
  (e.g., per-plane galvo voltages in a line-scan confocal).
- **`standard`** (default): Everything in `summary` plus channel
  excitation/emission, instrument metadata, and global acquisition timestamps.
  This is the right default — detailed enough for most questions, compact enough
  to stay well within LLM context limits.
- **`full`:** All metadata the format exposes, including per-plane timestamps,
  per-plane stage positions, scanner voltages, and any other key-value pairs.
  This can be very large and should only be requested when the user specifically
  needs it.

**Notes:**
- Should enumerate all series with basic info (name, dimensions) even when a
  specific series is selected, so the user knows what else is in the file.
- Metadata that Bio-Formats exposes via OME-XML should be parsed into structured
  fields, not returned as raw XML.
- When `detail` is not `full`, the response must include
  `omitted_metadata_bytes` so the LLM can tell the user that more metadata
  exists and offer to retrieve it.

### 2.2 `get_thumbnail`

**Purpose:** Generate a quick visual preview of the image data.

**Input:**
- `path` (string, required)
- `series` (integer, optional, default 0)
- `projection` (enum: `mid_slice` | `max_intensity` | `sum`, optional, default
  `max_intensity`) — for Z-stacks, whether to take the middle plane, a maximum
  intensity projection, or a sum through the stack.
- `channels` (array of integers, optional) — which channels to include.  If
  omitted, generates a composite of all channels.
- `timepoint` (integer, optional, default 0) — which timepoint to preview.
- `max_size` (integer, optional, default 1024) — maximum dimension in pixels for
  the output thumbnail.  The image is downsampled to fit.

**Output:**
- Base64-encoded PNG image, suitable for inline display by Claude or other
tools with inline display capability.

**Notes:**
- Channel compositing should use the color information from the file metadata
  when available, falling back to sensible defaults (green for single-channel,
  cyan/magenta/yellow for two/three channels, etc.).
- Downsampling should use a reasonable method (area averaging, not nearest
  neighbor) for quality previews.

### 2.3 `get_intensity_stats`

**Purpose:** Compute basic intensity statistics for quality assessment.

**Input:**
- `path` (string, required)
- `series` (integer, optional, default 0)
- `channel` (integer, optional) — if omitted, compute stats for all channels.
- `z_slice` (integer, optional) — if omitted, compute across all Z.
- `timepoint` (integer, optional, default 0)

**Output (JSON):**
- Per-channel: min, max, mean, standard deviation, median
- Per-channel: histogram (bin edges and counts, ~256 bins)
- Saturation warnings: percentage of pixels at the type minimum (potential
  clipping) or type maximum (saturation)
- Bit depth utilization: what fraction of the dynamic range is actually used

**Notes:**
- For large images, it is acceptable to compute stats on a downsampled version
  or a random subset of planes, as long as this is indicated in the output.
- Histogram data should be compact enough for the LLM to reason about but
  detailed enough to be useful.  256 bins for 16-bit data is a reasonable
  tradeoff.

### 2.4 `get_plane`

**Purpose:** Extract a specific 2D plane for detailed inspection.

**Input:**
- `path` (string, required)
- `series` (integer, optional, default 0)
- `channel` (integer, required)
- `z_slice` (integer, optional, default 0)
- `timepoint` (integer, optional, default 0)
- `normalize` (boolean, optional, default true) — whether to auto-contrast
  the image for display.  When false, maps the full type range to 0–255.
- `max_size` (integer, optional) — if provided, downsample to fit.

**Output:**
- Base64-encoded PNG image (single-channel grayscale).

**Notes:**
- This is intentionally single-channel to give the LLM (and user) a clear view
  of one data plane at a time.
- Auto-contrast should use a percentile-based stretch (e.g., 0.1th to 99.9th
  percentile) rather than min/max, to handle hot pixels gracefully.

### 2.5 `export_to_tiff`

**Purpose:** Export data to OME-TIFF for downstream use with standard tools.

**Input:**
- `path` (string, required) — source file.
- `output_path` (string, required) — destination `.ome.tif` or `.ome.tiff` path.
- `series` (integer, optional) — if omitted, convert all series.
- `channels` (array of integers, optional) — subset of channels.  If omitted,
  include all.
- `z_range` (object `{start, end}`, optional) — subset of Z slices (inclusive).
- `t_range` (object `{start, end}`, optional) — subset of timepoints (inclusive).
- `compression` (enum: `none` | `lzw` | `zlib`, optional, default `none`)

**Output (JSON):**
- Output file path
- Output file size
- Summary of what was written (dimensions, channels, etc.)

**Notes:**
- OME-TIFF is the target because it is the de facto open standard for
  microscopy data — it preserves metadata in a structured OME-XML header and is
  readable by virtually all scientific imaging software.
- Bio-Formats' writer infrastructure handles this natively.
- Should preserve as much OME metadata as possible from the source file.
- The tool must detect cases where the source file contains metadata that
  cannot be faithfully represented in the OME-TIFF output.  When this happens,
  the response should include a clear message listing what metadata was
  preserved and what was lost or degraded, so the user can make an informed
  decision about whether the export meets their needs.
- If zlib is not trivially deployable, remove it as an option for now.
- If parallel read/write of compressed TIFF becomes commonplace, switch
default to lzw (or zlib).  For now, it often becomes a sequential read
bottleneck for many downstream applications.


### 2.6 Resource Constraints

Microscopy files can be tens or hundreds of gigabytes.  Operations that touch
pixel data — thumbnails, stats, plane extraction, conversion — can easily exceed
reasonable time or memory limits if applied naively to an entire large dataset.

**Common budget parameter:** Every tool that reads pixel data accepts an
optional `budget` parameter:

- `budget` (object, optional) — resource limits for this call.
  - `max_bytes` (integer, optional) — approximate upper bound on the number of
    raw pixel bytes the tool will read.  The tool may subsample, crop, or
    truncate to stay within this limit.  If the limit forces the tool to skip
    data, the response must say what was skipped.
  - `timeout_seconds` (integer, optional) — wall-clock time limit.  If the
    operation cannot complete in this time, it should return a partial result
    (if meaningful) or an error explaining what happened.

Tools should have sensible built-in defaults so that callers who omit `budget`
get safe behavior.  The defaults should be conservative enough that a naive
`get_intensity_stats` call on a 200 GB file does not hang or OOM — it should
subsample and report that it did so.

The `inspect_image` tool does not need a `budget` parameter because its metadata
detail levels (§2.1) already control response size, and metadata extraction is
fast even for large files.

### 2.7 `get_ome_metadata`

**Purpose:** Return the file's complete extended metadata as a portable,
**format-tagged document** — the raw OME metadata block rather than the parsed
fields `inspect_image` returns.

**Output (JSON):** two strings — `format` (`ome_xml` or `ome_ngff`) and
`content` (the document). Bio-Formats synthesizes OME-XML from its OME model
for essentially every file it reads, so `ome_xml` is the universal case; this
is the most portable way to hand a file's metadata to other tools.

**Why a tagged `(format, content)` pair rather than just XML:** it keeps the
wire protocol stable as the representation evolves. A reader that can supply a
native **OME-NGFF** (OME-Zarr) JSON block — or a future synthesis step — returns
`ome_ngff` + JSON through the identical envelope, with no protocol change.
(Bio-Formats core cannot supply NGFF today: reading OME-Zarr needs the separate
`OMEZarrReader` add-on, and even then it normalizes through the OME model → OME-XML.)

**Size:** the document can be large (per-plane metadata), and unlike a pixel
read it cannot be meaningfully truncated — a partial XML/JSON is corrupt. So an
optional `max_response_bytes` cap turns an over-size document into an error that
reports the actual size (raise the cap to retrieve it) rather than a partial
result. The MCP transport defaults this cap (the document enters the LLM
context); the microservice transports do not.

The reader seam is `ImageReader.getMetadataBlock()`, whose default derives an
`ome_xml` block from `getOMEXML()`; a format-specific reader overrides it.


## 3. Technology Choices

### 3.1 Runtime Platform: JVM

**Decision:** JVM-based server, not Python.

**Rationale:**

The core value of the POC is *reading opaque proprietary microscopy formats and
making their contents accessible*.  That is exactly what Bio-Formats
(ome/bioformats) was built for.  Bio-Formats is a Java library that supports
150+ microscopy file formats and is the reference implementation used by FIJI,
OMERO, and most of the bioimage informatics ecosystem.

The Python alternatives fall into two categories:
- **Wrappers around Bio-Formats** (python-bioformats via javabridge) — these are
  notoriously brittle and still require a JVM anyway.
- **Native Python readers** (bioio/aicsimageio, tifffile, nd2, etc.) — these
  cover 10–15 formats natively.  Excellent for those formats, but insufficient
  for a tool whose value proposition is "give me any microscopy file."

Using Bio-Formats directly from the JVM avoids the interop tax entirely and
gives us the broadest, most reliable format coverage available.

The tradeoff is that future *computational* tools (segmentation, ML inference)
will want the Python scientific stack.  This is explicitly out of scope for the
POC and addressed in the extensibility plan (§6).

### 3.2 Implementation Language: Java (21+)

**Decision:** Modern Java, targeting Java 21 LTS.

**Rationale — candidates considered:**

- **Kotlin:** Good language, strong LLM training data, but lacks a scala-cli /
  JBang equivalent for zero-friction single-file execution.  Build tooling
  (Gradle) adds weight.
- **Scala 3 + scala-cli:** Excellent developer experience via scala-cli (inline
  dependency declarations, no build file needed for simple projects).  However,
  Bio-Formats is a Java library and all its documentation, examples, and
  community knowledge are in Java.  Scala adds an interop layer that, while
  usually transparent, can be a source of friction.
- **Java + JBang:** Same zero-friction execution model as scala-cli.  Direct,
  idiomatic access to Bio-Formats APIs — every code example is directly usable.
  Maximum LLM training data for both general Java and specifically Java +
  Bio-Formats.  Modern Java (records, sealed interfaces, pattern matching, text
  blocks, `var`) is expressive enough for this problem domain.

The deciding factor was the combination of JBang's developer experience (matching
scala-cli's ease of use), direct Bio-Formats compatibility, and LLM fluency in
the specific domain of Java + Bio-Formats code.

### 3.3 Build System: Mill 1.1 (preferred) or Gradle

**Decision:** Mill 1.1 is the preferred build system; Gradle is the fallback if
Mill proves problematic.

**Rationale:**

The project is a Java library with well-defined dependencies.  Mill's advantages:
- Concise, readable build definitions
- Fast dependency resolution
- No Gradle-style configuration-phase complexity
- Handles Java well (this is sometimes overlooked due to Mill's Scala origins)
- If Scala or Kotlin modules are added later, Mill handles polyglot builds
  gracefully

The risk is that LLMs have much less Mill training data than Gradle, which could
slow LLM-assisted build configuration.  However, build files are a small surface
area — set up once, touched rarely — so this is a manageable tradeoff.

### 3.4 Runner / Distribution: JBang

**Decision:** A thin JBang wrapper file serves as the user-facing entry point.

**Rationale:**

JBang provides:
- Single-file execution with inline dependency declarations
  (`//DEPS org.bioimage:mcp-server:0.1.0`)
- Automatic JVM provisioning if needed
- Possibility of per-machine customization (e.g. with bytedeco)
- Dependency resolution via Maven coordinates
- No build step, no project structure, no IDE required for end users

The user experience is: install JBang (one command), obtain the runner file (one
file), run it.  The runner file is potentially small enough to paste into a
Claude Desktop MCP configuration directly as a command.

### 3.5 MCP Transport: stdio

**Decision:** stdio transport for the POC.

**Rationale:**

- Simplest implementation — no ports, networking, or auth.
- Best supported by current MCP clients (Claude Desktop, Claude Code).
- Natural fit for a local developer tool.
- Most existing MCP servers use stdio, so there is ample reference material.
- Can be upgraded to HTTP/SSE later without changing tool logic — only the
  transport layer changes.

**Note:** For long-running operations (large file reads, conversions), the
implementation should use MCP's progress notification mechanism so the client
knows work is happening.

**Alternatives considered:**

- *SSE over HTTP* — better for long-lived servers, shared lab resources, or
  multi-client scenarios.  More infrastructure to manage.  A natural second
  transport to add once the tool surface is stable.
- *Streamable HTTP* — the newer MCP transport, cleaner than SSE, but client
  tooling support is still maturing.


## 4. Repository Structure

The repository contains two logical components:

### 4.1 The Library (build system artifact)

A Mill (or Gradle) project that compiles to a JAR published to Maven Central via
Sonatype.  This contains all the actual server logic: MCP protocol handling,
Bio-Formats integration, tool implementations.

```
bioimage-mcp/
├── build.mill                    # Mill build definition
├── runner/
│   └── bioimage-mcp.java        # JBang runner (thin launcher)
├── src/
│   └── org/bioimage/mcp/
│       ├── BioImageMcpServer.java    # Entry point, MCP protocol handling
│       ├── tools/
│       │   ├── InspectImage.java
│       │   ├── GetThumbnail.java
│       │   ├── GetIntensityStats.java
│       │   ├── GetPlane.java
│       │   └── ExportToTiff.java
│       ├── formats/
│       │   └── BioFormatsReader.java  # Bio-Formats abstraction layer
│       ├── model/
│       │   ├── ImageMetadata.java     # Records for structured metadata
│       │   ├── ChannelInfo.java
│       │   ├── PixelSize.java
│       │   └── ...
│       └── protocol/
│           └── ...                    # MCP protocol types and handling
├── test/
│   └── ...
├── DESIGN.md                     # This document
└── README.md
```

### 4.2 The Runner

A single JBang-compatible Java file (`runner/bioimage-mcp.java`) that declares
the Maven dependency and boots the server.  This is what end users actually
execute.

It also provides a place to add customizaton options without needing to
maintain additional customization files.

**For end users** (pointing at a published release):
```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.bioimage:mcp-server:0.1.0

import org.bioimage.mcp.BioImageMcpServer;

public class bioimage_mcp {
    public static void main(String[] args) {
        BioImageMcpServer.main(args);
    }
}
```

**For developers** (pointing at a local build):

After `mill publish-local` (or equivalent), either:
- Temporarily edit the `//DEPS` line to use the snapshot version, or
- Use `mill assembly && jbang --cp out/assembly.dest/out.jar bioimage-mcp.java`
  to point at the local build output (the fat jar Mill writes there), or
- Run directly via Mill: `mill run`

The README should document both workflows clearly.


## 5. File Access and Trusted Roots

The server reads files from the local filesystem and must not be an open door to
arbitrary file access.  Defense in depth applies, but even cooperative safety
(where components respect declared boundaries rather than enforcing them via OS
mechanisms) is far better than nothing.

### 5.1 MCP Client Roots

The MCP protocol allows clients to declare *roots* — filesystem paths that the
client considers in scope for the session.  The server should respect these:

- On startup, the server reads the client's declared roots.
- Any tool invocation that references a file path must resolve to a location
  under one of the declared roots.  Requests for paths outside all roots are
  rejected with a clear error message.
- Symlinks and `..` components are resolved before checking, so they cannot be
  used to escape the roots.

This is cooperative safety — the client declares what it considers permitted, and
the server honors that declaration.  It does not protect against a malicious
client, but it prevents accidental access to unrelated parts of the filesystem
and gives the user a clear contract about what the server can touch.

### 5.2 User-Declared Path Rules

Independent of client roots, the user may want to grant or restrict access to
specific paths — for example, whitelisting a data directory that the client
doesn't know about, or blacklisting a sensitive directory that happens to fall
under a client root.

The server supports two lists:

- **Allow-list (whitelist):** Additional paths the server may access, even if
  they are not under any client root.  Useful for pointing the server at a data
  store the client is unaware of.
- **Deny-list (blacklist):** Paths the server must refuse to access, even if
  they fall under a client root or an allow-list entry.  The deny-list takes
  precedence over all other access grants.

**Resolution order:** A path is accessible if and only if:
1. It is not under any deny-list entry, AND
2. It is under a client root OR under an allow-list entry.

### 5.3 Configuration via the Runner

The JBang runner file (`runner/bioimage-mcp.java`) is a natural place for users
to declare their path rules.  Because the runner is a small, user-editable file
that is already per-machine, adding allow/deny lists there keeps configuration
local and visible without requiring a separate config file.  For example:

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.bioimage:mcp-server:0.1.0

import org.bioimage.mcp.BioImageMcpServer;

public class bioimage_mcp {
    public static void main(String[] args) {
        BioImageMcpServer.builder()
            .allow("/data/microscopy")
            .allow("/shared/lab-images")
            .deny("/data/microscopy/private")
            .build()
            .run(args);
    }
}
```

Command-line arguments or environment variables are alternative mechanisms, but
the runner file has the advantage of being self-documenting and
version-controllable per machine.

### 5.4 Identity, Authentication & Multi-User

Path access control (§5.1–5.3) answers *which files* a server may touch.  It
does **not** answer *who may talk to the server* — a separate, transport-level
concern that matters as soon as more than one user shares a machine.  The
guiding principle mirrors the rest of the project: **narrow by default;
flexibility is an explicit ask.**

Security here is two-sided, and one helper (`LocalEndpoint`) covers both:

- **Keep other users out.**  The network transports use a ~256-bit random
  **token** (gRPC always; HTTP opt-in) checked in constant time.  Token,
  descriptor, and socket files live in a **per-user** directory with owner-only
  permissions, so only the same OS user can read the secret or reach the socket.
- **Let the right user in — without colliding.**  After binding, a server
  publishes a small **descriptor file** — `{"port", "token"}` — into that
  per-user directory.  The same user's client reads *one* file to learn *where*
  to connect *and* the secret to present.  Because each user has their own
  runtime directory, two users running their own instances never collide and
  never see each other's descriptor.  gRPC additionally binds an **ephemeral
  port** by default, so a fixed-port `EADDRINUSE` clash is impossible; several
  same-user instances are disambiguated with `--instance <name>`.

**Per-transport posture:**

| Transport | Default identity | Discovery | Opt-outs / opt-ins |
|-----------|------------------|-----------|--------------------|
| **gRPC**  | token required; loopback; ephemeral port | per-user descriptor `bioimage-grpc.json` | `--port` pins; `--instance` names; `--insecure` drops the token |
| **HTTP**  | **exposed** (all interfaces, no token) — that *is* its point | fixed port 8722 | `--bind <addr>` narrows the interface; `--require-token` demands a token (printed to stderr; `/health` stays open); `--port` for a second user |
| **Socket**| user-only by **filesystem** perms (0600 socket in a 0700 per-user dir); no token | per-user socket path | `--socket` sets an explicit path |
| **MCP**   | stdio: each client spawns its own subprocess — inherently per-user/per-client | n/a | n/a |

**Where the per-user directory is (per-user by construction):** `$XDG_RUNTIME_DIR`
(`/run/user/<uid>`, 0700) on Linux; `$TMPDIR` (`/var/folders/…/T/`, per-user
0700) on macOS; `%LOCALAPPDATA%\Temp` (profile, ACL'd) on Windows.  On a non-XDG
platform a `bioimage-<user>/` subdirectory is used so secrets are never dropped
bare into a shared `/tmp`; a pre-existing such directory is rejected (POSIX)
unless it is owned by us and not group/world-accessible (anti-squat).

**Cross-platform note.** File-mode enforcement is real (0600/0700) on Linux and
macOS.  On Windows (NTFS, no POSIX view) we rely on the user-profile directory's
inherited ACL rather than setting an explicit DACL — local administrators can
read the files there, as they can anywhere.  The **token**, not the file mode,
is the actual network gate, so functionality is identical across platforms;
only the depth of the file-layer defense varies.  (The Windows file-perm path is
best-effort and currently untested.)


## 6. Extensibility Plan

### 6.1 Adding More JVM-Native Tools

Additional tools that only need Bio-Formats and standard Java libraries can be
added directly — e.g., ROI extraction, multi-series comparison, time-series
summary, mosaic/tile-map analysis.  These are just new tool classes following the
same pattern.

Note that ImageJ contains many diverse capabilities and plugins, many of
which are accessible programmatically and downloadable from repositories.

### 6.2 Python Integration for Computational Tools

When the project grows to need Python-based computation (segmentation via
cellpose/stardist, deconvolution, ML inference), the architecture should be:

**The JVM server remains the single MCP interface point.**  Claude talks to one
server.  That server delegates to Python when needed.

**Subprocess invocation is the pragmatic first approach.**  The JVM server calls
a Python script via `ProcessBuilder`, passing data via file paths (e.g., a
temporary OME-TIFF exported by `export_to_tiff`) and receiving results as
JSON on stdout.  This is simple, debuggable, and avoids complex IPC setup.

**Amdahl's law justification:** In an LLM-driven workflow, the round-trip
through the LLM is 5–30 seconds per tool call.  A 2–3 second Python cold start,
or even a 10-second model load, is proportionally minor.  The latency tolerance
inherent in conversational AI interaction means that process-startup overhead
that would be unacceptable in an interactive GUI is perfectly fine here.

**If subprocess overhead ever matters** (batch workflows with many rapid calls),
a persistent Python sidecar communicating over a local socket (ZeroMQ, Unix
domain socket, or local HTTP) is the natural next step.  But this is an
optimization to defer.

**Code preparation:** The tool dispatch layer should use an interface/trait that
abstracts over "how this tool runs," so that adding a Python-backed tool doesn't
require restructuring existing code.  Something like:

```java
sealed interface ToolBackend {
    record JvmTool(/* ... */) implements ToolBackend {}
    record PythonTool(String scriptPath, /* ... */) implements ToolBackend {}
}
```

### 6.3 Additional Transports

The stdio transport can be supplemented with HTTP/SSE for shared-server
deployments (e.g., a lab server that multiple researchers connect to).  The tool
logic is transport-independent; only the protocol handling layer changes.

### 6.4 OMERO Integration

A natural future direction is connecting to OMERO servers — browsing projects,
datasets, and images; pulling data for local analysis; pushing results back.
This would be a separate set of tools using the OMERO Java client libraries,
which are well-maintained and Maven-published.


## 7. Key Dependencies

| Dependency              | Purpose                                   | Coordinates (approximate)                    |
|-------------------------|-------------------------------------------|----------------------------------------------|
| Bio-Formats (GPL)       | Microscopy format I/O                     | `ome:formats-gpl:7.x`                       |
| OME Common              | OME-XML metadata model                    | `ome:ome-common:6.x`                        |
| MCP Java SDK            | MCP protocol handling                     | `io.modelcontextprotocol.sdk:mcp:1.x`       |
| JBang                   | Runner / launcher                         | (build-time / user tooling, not a dep)       |

**MCP Java SDK:** The official Java MCP SDK
(https://github.com/modelcontextprotocol/java-sdk) is maintained under the
MCP GitHub organization in collaboration with the Spring team.  It provides
sync and async server APIs, built-in stdio and HTTP transports, and Jackson-
based serialization.  Documentation is at
https://modelcontextprotocol.github.io/java-sdk/.  The `mcp` artifact bundles
the core SDK with Jackson and is the primary dependency for this project.  We
do not use the Spring Boot starters — this is a plain Java application.

**Note on Bio-Formats licensing:** Bio-Formats is available under GPL-2.0.  The
`formats-gpl` artifact includes all readers.  The `formats-bsd` artifact covers
a smaller set of formats under BSD-2-Clause.  This project is licensed under
GPL-3.0, which is compatible with Bio-Formats' GPL-2.0.

**Architectural isolation:** Although the entire project is GPL for now, all
Bio-Formats API usage must be confined to the `formats/` package.  The rest of
the codebase — tool implementations, MCP protocol handling, model records —
depends only on interfaces and records defined outside `formats/`, never on
Bio-Formats types directly.  This keeps the Bio-Formats dependency behind a
clean abstraction boundary so that if a licensing separation is ever needed
(e.g., offering a BSD-licensed core with a GPL plugin for proprietary format
readers), the work is a matter of swapping implementations rather than
untangling interleaved code.


## 8. Open Questions

These should be resolved during implementation:

1. **Image return format.** MCP supports returning images as base64-encoded
   content.  Need to confirm the exact content type handling across MCP clients
   (Claude Desktop, Claude Code) and ensure PNG thumbnails display correctly.

2. **File access model.** The POC assumes the server has filesystem access to
   the image files (appropriate for a local stdio server).  For future
   HTTP-transport deployments, a file-upload or path-mapping mechanism would be
   needed.

3. **Concurrency model.** Bio-Formats readers are not thread-safe.  For the
   stdio POC (single client, sequential requests) this is not an issue.  For
   future HTTP/multi-client scenarios, reader pooling or per-request reader
   instantiation will be needed.  Readers are usually relatively inexpensive
   save possibly for buffers.  Leveraging Java 21+ virtual threads by
   default should enable a safe performant solution.

4. **Maven coordinates and group ID.** The actual group ID for Maven Central
   publication needs to be decided (e.g., `org.bioimage`, `io.github.<user>`,
   etc.).

5. **Mill vs Gradle final decision.** Evaluate Mill 1.1's Java support
   concretely with the actual dependency set (Bio-Formats has complex transitive
   dependencies).  Fall back to Gradle if issues arise.


## 9. Shared-Memory Deposit Protocol

The microservice transport (`BioImageSocketService`) lets a co-located
client receive **raw pixel volumes** with no copy through a socket body and
no PNG/JSON re-encoding.  It is the data-plane complement to the MCP and
HTTP adapters, built on the same protocol-neutral `BioImageService`
(`deposit` operation, `DepositDescriptor`, `PixelSink`).

### 9.1 Two planes

- **Control plane** — a persistent **Unix-domain socket** carrying
  newline-delimited JSON (one object per line, each direction).
  `UnixDomainSocketAddress` works on Linux and Windows 10+; loopback TCP is
  the fallback if a platform lacks UDS.
- **Data plane** — a **client-owned region**: a file the client creates,
  sizes, maps (`mmap`) into its own address space, and later unlinks.  On
  `tmpfs`/`/dev/shm` its pages are RAM, so the server's positional writes
  are visible to the client's mapping with no disk round-trip.  Pixel bytes
  never travel over the socket.

The server attaches to the region only for the duration of one deposit
(opens the file, writes, flushes, closes) and **never unlinks it** —
lifecycle management is entirely the client's.

### 9.2 Ownership and lifecycle decisions

- **Client owns the region.** It creates and pre-sizes the file and passes a
  `capacity_bytes`.  The server computes `required = T·Z·C·Y·X·bytesPerSample`
  and **refuses** (`INVALID_ARGUMENT`, writing nothing) if
  `required > capacity_bytes` — no false confidence that a short buffer
  "worked".  The server maps exactly `required` and never grows the region.
- **Reply means ready.** The server responds `filled` only after every byte
  is written and flushed; no `filled` ⇒ the region is incomplete and the
  client must discard it.  There is no separate ready flag.
- **Drop on disconnect.** The connection thread only reads, so a client
  close is observed promptly as EOF; an in-flight deposit is cancelled
  (interrupt-with-backoff via `CancellableTask.Handle`), the `PixelSink` is
  always closed, and no reply is sent.
- **Native endianness.** Bytes are written in the source file's order;
  `little_endian` in the descriptor reports which (never byte-swapped).
- **Access control.** Both the source image path and the target region path
  pass the same deny > allow > client-roots check as every other operation,
  so a crafted `target.path` cannot induce the server to write outside
  permitted directories.  Clients typically `--allow /dev/shm`.
- **Connection identity (user-only).** The socket needs no token: it is
  user-only by **filesystem** identity.  The control socket is bound inside a
  per-user 0700 directory (`$XDG_RUNTIME_DIR`, or a `bioimage-<user>/` subdir of
  the temp dir on non-XDG platforms — never bare in a shared `/tmp`) and the
  socket file itself is set 0600, so another local user cannot connect.  The
  0700 directory is the primary guarantee (created/verified before the bind, so
  there is no reachable window) and the 0600 socket is defense-in-depth; both are
  no-ops on non-POSIX filesystems, where the per-user profile-directory ACL
  applies instead (see §5.4).  Each user thus gets a distinct socket path —
  instances never collide.

### 9.3 Buffer layout

Pixels are written in **C-order with X fastest and T slowest**, in the
**NGFF / OME-Zarr canonical axis order** `["t","c","z","y","x"]` (time, then
channel, then the spatial axes).  The element at `(t,c,z,y,x)` — indices
relative to the *deposited selection*, not the source file — lives at byte
offset

```
((((t*sizeC + c)*sizeZ + z)*sizeY + y)*sizeX + x) * bytesPerSample
```

We deliberately normalize to this order (not the source file's arbitrary
`dimensionOrder`, which is generally not NGFF-compliant) so a mapped region
drops straight into an OME-Zarr / NGFF consumer (napari, zarr, dask) without
a transpose, and each channel's full Z-stack is a contiguous (channel-major)
block.

Each `readPlane` result is one `(t,c,z)` plane of `sizeY*sizeX` samples in
row-major order, copied verbatim at `planeIndex * planeBytes`.  A "volume"
is simply a deposit with a full Z-range and a single channel/timepoint;
there is no native multi-plane read in Bio-Formats, so the server loops
planes and composes the contiguous buffer itself.

### 9.4 Messages

On connect the server sends a hello:

```json
{"type":"ready","protocol":1,"service":"bioimage-socket","version":"0.4.0"}
```

**Deposit** (one in flight per connection, sequential; `id` is client-chosen
and echoed):

```json
{"type":"deposit","id":"c1",
 "path":"/data/stack.czi","series":0,
 "channels":"0",                   // slice selection (required): ":" = all,
 "z":":",                          //   "0,2" = list, "4:9" = range, etc.
 "t":"0",                          //   omitting any of these is an error
 "target":{"kind":"file","path":"/dev/shm/bio-7f3a","capacity_bytes":43352064},
 "timeout_seconds":60}
```

Set `"dry_run":true` (and omit `target`) to get the descriptor — with
`total_bytes` — *without* writing, so the client can size the region first.

**Filled** (the success reply; carries the full `DepositDescriptor`):

```json
{"type":"filled","id":"c1",
 "offset":0,"total_bytes":43352064,"plane_bytes":688128,
 "pixel_type":"uint16","bytes_per_sample":2,"signed":false,"little_endian":true,
 "axis_order":["t","c","z","y","x"],
 "shape":{"x":672,"y":512,"c":1,"z":21,"t":1},
 "selection":{"t":[[0,1]],"c":[[0,1]],"z":[[0,21]],"y":[[0,512]],"x":[[0,672]]}}
```

`shape` gives the per-axis *counts*; `selection` gives the actual **source
indices** delivered on every axis, in buffer order, as run-length
`[start, stop)` ranges.  The counts alone are not enough to interpret the
bytes when a channel/Z/T selection is a non-contiguous or reordered list — e.g.
`channels:"0,2,5"` yields `"c":[[0,1],[2,3],[5,6]]`, so buffer channel 0/1/2 map
to source channels 0/2/5.  X and Y are always the full plane here, but are
reported anyway so the format is general (a server that crops X/Y fills in real
sub-selections through the same field).

**Error** (`error_kind` is `access_denied` | `invalid_argument` | `timeout`
| `io_error` for operation failures):

```json
{"type":"error","id":"c1","error_kind":"invalid_argument","message":"..."}
```

**Shutdown** — a client may ask the server to exit, honored **only when the
requester is the sole connected client** so one client can never tear the
server out from under others:

```json
{"type":"shutdown","id":"s1"}
→ {"type":"shutdown_ok","id":"s1"}          // then the server exits
→ {"type":"error","id":"s1","error_kind":"shutdown_refused",
   "message":"refusing shutdown: N other client(s) connected"}
```

On `shutdown_ok` the server closes the listener, removes the socket file
(via its shutdown hook), and exits; the client observes the reply followed
by EOF.  The server flushes `shutdown_ok` and then exits without waiting to
confirm delivery — a robust client must already tolerate the server
vanishing at any moment (crash, OOM, host reboot), so "I requested shutdown
and the final bytes didn't arrive" is just one case of that general
responsibility, not a special one worth a delivery handshake.

### 9.5 Future sink kinds

`target.kind` is a tagged union; `"file"` (`MappedFileSink`) is the only v1
implementation.  A true POSIX `shm_open` object (`"posix_shm"`) or a Windows
named mapping (`"win_named"`) can be added as new `PixelSink`s behind the
same envelope — via the Foreign Function & Memory API — without any change
to the wire protocol or the deposit logic.  The file-backed path is
preferred until profiling shows the `tmpfs` indirection matters, because it
is one cross-platform code path with no FFM and no 2 GB
`MappedByteBuffer` limit (positional `FileChannel` writes take `long`
offsets).


## 10. Stateful Sessions (kept-open readers)

Every operation in §2 is **stateless**: it opens a fresh Bio-Formats reader,
does one thing, and closes it.  That is the right default for the
request/response transports (MCP/stdio, HTTP), but it is wasteful for the
persistent-connection transports, where a client typically keeps working with
the *same* image — reading many planes, depositing several volumes.  Re-opening
and re-parsing the file on every call repeats the most expensive metadata work.

A **session** lets a client open an image once and reuse the open reader.

### 10.1 Model

- `open` validates a `path`, opens a reader, reads its SUMMARY metadata, and
  registers an `ImageSession` keyed by a server-generated **handle** (a UUID).
  It returns `{handle, summary}`.
- Any read operation (`inspect_image`, `get_plane`, `get_intensity_stats`,
  `get_thumbnail`, `deposit`) may then carry `handle` **instead of** `path`,
  and runs against the already-open reader.  `export_to_tiff` stays path-only
  (a one-shot write, no reuse benefit).
- `close` removes the session and closes the reader.

The seam in `BioImageService` is deliberately small.  A `withSession(args,
body)` helper routes each read op: with no `handle` it calls the body with the
normal per-call reader factory (today's behavior, unchanged); with a `handle`
it holds the session lock, injects the session's canonical `path`, and supplies
a `HeldImageReader` factory.  `HeldImageReader` is an `ImageReader` over the
session's open reader whose `open()` and `close()` are **no-ops** — so the
existing tools, which all do `try (var r = factory.get()) { r.open(path); … }`,
run unchanged without re-opening or closing the shared reader.

### 10.2 Ownership, concurrency, lifecycle

- **The transport owns the lifetime, the service owns the registry.**
  `BioImageService` holds the `handle → ImageSession` map and the open/close
  operations; it does not decide *when* a session dies.  A session-capable
  transport tracks the handles opened on each connection and calls
  `closeSession` for each when the client closes them or the connection drops.
  This is the same per-connection ownership the deposit cancellation already
  uses.
- **Disconnect closes the reader.**  This is the core guarantee: an `open`
  reader is never leaked.  The socket adapter closes a connection's handles in
  its `finally`; the gRPC adapter does so on stream `onError`/`onCompleted`.
- **Per-session serialization.**  Bio-Formats readers are not thread-safe, so
  every operation on a session holds the session's `ReentrantLock`.
  `closeSession` also takes the lock, so the reader is never closed out from
  under an in-flight read (the transport cancels an in-flight deposit first;
  `closeSession` then waits for it to unwind before closing).
- **Access control still applies.**  The path is checked at `open`, and the
  (stable) deny/allow policy is re-applied on each handle operation; a handle
  is not a capability that bypasses the policy.

### 10.3 Which transports

Sessions are exposed on the **persistent-connection** transports only —
the UDS socket (§9) and gRPC (§11).  HTTP has no connection to own a session's
lifetime (a leaked handle would have no natural close trigger), and the stdio
MCP server is single-client/sequential, so the reuse win does not justify the
added surface.  Both stay stateless.


## 11. Local gRPC Transport

Some access patterns are inherently stateful: if you hold an open connection,
you very likely want to keep reading the same image.  gRPC is a natural fit —
a typed, streaming, widely-supported RPC layer — and pairs well with the
shared-memory deposit for the bulk data.

### 11.1 Shape

`BioImageGrpcService` is a fourth sibling adapter over `BioImageService`
(alongside MCP, HTTP, socket): all transport, no image logic.  It exposes a
single bidirectional RPC

```proto
service BioImage { rpc Session(stream ClientMsg) returns (stream ServerMsg); }
```

whose stream lifetime **is** the session-owning connection — the exact
analogue of the socket adapter's persistent NDJSON connection.  `ClientMsg` /
`ServerMsg` are `oneof` envelopes carrying the same operations and the same
string slice-selections used on every other transport.  The wire contract is
`src/proto/bioimage.proto`; the Java stubs are generated at build time (see
§11.3).

- **Control plane:** the protobuf stream.  Inspect/stats results return as JSON
  strings (`JsonUtil` is the single source of truth for that shape rather than
  re-modelling all metadata in protobuf); plane/thumbnail return PNG bytes
  inline (acceptable on a local link).
- **Data plane:** a `deposit` writes raw pixels into the same client-owned
  shared-memory region as §9; only the `DepositDescriptor` (`Filled`) crosses
  the wire.

### 11.2 Lifecycle and local-only binding

gRPC delivers a stream's `onNext` calls serially.  Read ops are answered
inline; a `deposit` is awaited on a worker and its reply written back under a
write-lock, so the stream stays responsive (one deposit in flight per stream).
On stream `onError`/`onCompleted` the adapter cancels any in-flight deposit and
closes every handle the stream opened — closing the kept-open readers.  A
`shutdown` message is honored only when the requester is the sole connected
stream, mirroring the socket adapter.

The server binds the **loopback interface only** (`127.0.0.1`), via
`grpc-netty-shaded` — cross-platform, no native epoll dependency, no TLS.
Loopback is machine-local but **not user-only**, so by default the server
requires a per-user **auth token** and binds an **ephemeral port**, publishing
`{port, token}` to a per-user descriptor file the same user's client reads (see
§5.4).  The token rides in the call's `authorization` **metadata** — it is *not*
part of `bioimage.proto`, so this adds no schema change and an old client simply
fails the auth check rather than failing to compile.  A `ServerInterceptor`
(plain `io.grpc.*`; only the netty *transport* is shaded) rejects a missing or
wrong token with `UNAUTHENTICATED`.  `--insecure` drops the token; `--port` pins
a fixed port.  A remote/secured gRPC endpoint (TLS, UDS via native transport,
stronger authn) is a future extension that does not change the tool logic.

### 11.3 Build: Maven-fetched protoc toolchain

The build (`build.mill`, Scala) resolves the `protoc` compiler
(`com.google.protobuf:protoc`) and the gRPC plugin
(`io.grpc:protoc-gen-grpc-java`) from Maven as OS-classified `exe` artifacts,
runs them in a `protocGenerate` task, and feeds the generated Java into
`generatedSources`.  No system `protoc` is required, and the whole toolchain
is version-pinned in lockstep with the grpc-java/protobuf-java runtime (grpc
1.81.0 / protobuf 3.25.8).  Converting from the former `build.mill.yaml` to
Scala was necessary because the YAML build format cannot express a custom
code-generation task.


## 12. Protocol Governance

We expose four transports over one protocol-neutral core, which raises the
question every multi-transport, multi-client system faces: **how do N clients
and M servers stay interoperable on one protocol without drift?**  Our answer
rests on a deliberate split of authority.

### 12.1 Sovereign vs. conformance surfaces

- **Socket and HTTP are sovereign surfaces.**  We define them, we own their
  wire formats, and we change them on our terms.  They carry the full feature
  set at full fidelity.
- **gRPC is a conformance surface.**  gRPC's value is interoperability — a
  client that consumes many gRPC servers wants them to speak one schema.  So if
  a sufficiently important client (or an emerging community standard) defines an
  imaging-server contract, the right move is for us to **conform to it** rather
  than impose our own.  We can afford this precisely because socket and HTTP
  remain under our undisputed authority: being a polite guest on gRPC costs us
  no control over our native API.

This is the same pattern as CSI (Kubernetes defines the gRPC contract; storage
vendors conform), Envoy xDS, and LSP/DAP: the important *consumer* owns the
contract and ships a conformance test; *implementers* conform.

### 12.2 Why conforming is cheap here

Because {@code BioImageService} is protocol-neutral, conforming to someone
else's gRPC contract is **another thin adapter** against the same core —
exactly how {@code BioImageGrpcService} was written against our own
{@code bioimage.proto}.  The image logic never moves.  Ownership of the schema
can therefore stay undecided until a real consumer appears: today we own
{@code src/proto/bioimage.proto} (server-authoritative); switching to
consumer-authoritative later is an adapter swap, not a rewrite.

### 12.3 Keeping one protocol stable across N×M

The `.proto` *is* the contract, and three disciplines keep it interoperable:

1. **One source of truth, not copies.**  Clients and servers import the same
   schema (published stubs, a shared repo, or a registry) rather than vendoring
   divergent copies.
2. **Wire-compatibility rules.**  Protobuf's guarantees — never reuse field
   numbers, add only `optional` fields, never change a field's type, ignore
   unknown fields — let an old client talk to a new server and vice-versa.
   That is what makes loose N×M coupling survive change.
3. **Versioned packages.**  The package is `…bioimage.v1`; a genuine break ships
   `…v2` as a *parallel* service run alongside `v1` during migration — never a
   flag day.

**Tooling.**  We lint the schema with the **buf CLI**, fetched from Maven
Central (`build.buf:buf`, same OS-classified `exe` scheme as protoc) and run as
`mill bufLint` — a standalone gate, not part of compile, and **CLI-only with no
Buf Schema Registry dependency**.  `buf.yaml` excepts the few STANDARD rules
that conflict with deliberate design choices (the single bidi `Session` stream
with `ClientMsg`/`ServerMsg` envelopes, and the flat `src/proto` layout).
Breaking-change detection (`buf breaking`) is deferred until `v1` is frozen —
while we are still making intentional breaks pre-release it would only be noise.

### 12.4 Conformance is semantic, not just syntactic

The schema does not capture everything two implementations must agree on.  The
deposit **axis order is TCZYX** (§9.3) — the descriptor carries `axis_order` as
*data*, but "you MUST emit `[t,c,z,y,x]`" is a semantic rule.  Likewise the
error-kind vocabulary, the "missing slice is an error" rule, the capacity
refusal that writes nothing, and the `max_response_bytes` behavior.  These live
in the conformance spec — `service-endpoints.md` — which any conforming server
(ours or a third party's) must satisfy beyond merely compiling against the
`.proto`.

### 12.5 Accepting a richer contract while reporting reduced capability

A conformance surface must let a client send fields aimed at *more-capable*
servers without forcing per-server request shapes — provided the server never
misrepresents what it did.  The gRPC `DepositRequest` does exactly this with
three fields it does not honor:

- **`y` / `x` sub-ranges** — accepted but ignored (Bio-Formats is plane-based, so
  the full plane is served).  No deception is possible because the `Filled`
  descriptor's `shape` reports the actual extent on every axis; a client that
  asked for a sub-range sees the full extent it received.
- **`level`** (pyramid tier) — only level 0 (full resolution) is served; a
  non-zero level is **refused**, never silently downgraded.  Silently serving
  level 0 for a level-2 request would be false confidence (§ Project outlook);
  an explicit refusal is the honest outcome.

The rule that makes this safe is the same one that governs the whole project:
**always report what was actually delivered, on every axis.**  Given that, a
server may accept a superset of the protocol it implements — the descriptor, not
the request, is the source of truth.  These concessions are gRPC-only; the
sovereign socket/HTTP surfaces carry only what they implement.
