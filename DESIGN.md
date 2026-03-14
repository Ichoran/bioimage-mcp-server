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

### 3.2 Implementation Language: Java (25+)

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
- Use `jbang --cp $(mill show assembly) bioimage-mcp.java` to point at the local
  build output, or
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
