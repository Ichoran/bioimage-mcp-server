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

**Notes:**
- Should enumerate all series with basic info (name, dimensions) even when a
  specific series is selected, so the user knows what else is in the file.
- Metadata that Bio-Formats exposes via OME-XML should be parsed into structured
  fields, not returned as raw XML.

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

### 2.5 `convert_to_tiff`

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
- If zlib is not trivially deployable, remove it as an option for now.
- If parallel read/write of compressed TIFF becomes commonplace, switch
default to lzw (or zlib).  For now, it often becomes a sequential read
bottleneck for many downstream applications.


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
POC and addressed in the extensibility plan (§5).

### 3.2 Implementation Language: Java (25+)

**Decision:** Modern Java, targeting Java 25, the latest LTS version.

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
│       │   └── ConvertToTiff.java
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


## 5. Extensibility Plan

### 5.1 Adding More JVM-Native Tools

Additional tools that only need Bio-Formats and standard Java libraries can be
added directly — e.g., ROI extraction, multi-series comparison, time-series
summary, mosaic/tile-map analysis.  These are just new tool classes following the
same pattern.

Note that ImageJ contains many diverse capabilities and plugins, many of
which are accessible programmatically and downloadable from repositories.

### 5.2 Python Integration for Computational Tools

When the project grows to need Python-based computation (segmentation via
cellpose/stardist, deconvolution, ML inference), the architecture should be:

**The JVM server remains the single MCP interface point.**  Claude talks to one
server.  That server delegates to Python when needed.

**Subprocess invocation is the pragmatic first approach.**  The JVM server calls
a Python script via `ProcessBuilder`, passing data via file paths (e.g., a
temporary OME-TIFF exported by `convert_to_tiff`) and receiving results as
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

### 5.3 Additional Transports

The stdio transport can be supplemented with HTTP/SSE for shared-server
deployments (e.g., a lab server that multiple researchers connect to).  The tool
logic is transport-independent; only the protocol handling layer changes.

### 5.4 OMERO Integration

A natural future direction is connecting to OMERO servers — browsing projects,
datasets, and images; pulling data for local analysis; pushing results back.
This would be a separate set of tools using the OMERO Java client libraries,
which are well-maintained and Maven-published.


## 6. Key Dependencies

| Dependency              | Purpose                                   | Coordinates (approximate)                    |
|-------------------------|-------------------------------------------|----------------------------------------------|
| Bio-Formats (GPL)       | Microscopy format I/O                     | `ome:formats-gpl:7.x`                       |
| OME Common              | OME-XML metadata model                    | `ome:ome-common:6.x`                        |
| MCP Java SDK (if one exists) | MCP protocol handling               | TBD — may need to implement protocol directly|
| JSON library            | Serialization (Jackson, Gson, or similar) | TBD                                          |
| JBang                   | Runner / launcher                         | (build-time / user tooling, not a dep)       |

**Note on MCP SDK availability:** At time of writing, the MCP ecosystem is
young and a mature Java MCP SDK may or may not exist.  If not, the stdio MCP
protocol is simple enough (JSON-RPC 2.0 over stdin/stdout) to implement
directly.  This should be evaluated at implementation time.

**Note on Bio-Formats licensing:** Bio-Formats is available under GPL-2.0.  The
`formats-gpl` artifact includes all readers.  The `formats-bsd` artifact covers
a smaller set of formats under BSD-2-Clause.  The GPL version is appropriate for
a standalone tool; if the library is to be embedded in proprietary software, the
BSD subset or a licensing arrangement with OME would be needed.  Initially
the project will be licensed as GPL, but it should be split into
GPL-requiring and GPL-independent parts if there is a major component that
does not depend substantially on Bio-Formats.


## 7. Open Questions

These should be resolved during implementation:

1. **MCP Java SDK status.** Is there a usable Java/Kotlin MCP SDK, or do we
   implement the protocol directly?  The protocol is straightforward JSON-RPC
   2.0, so direct implementation is feasible but an SDK would save boilerplate.

2. **Image return format.** MCP supports returning images as base64-encoded
   content.  Need to confirm the exact content type handling across MCP clients
   (Claude Desktop, Claude Code) and ensure PNG thumbnails display correctly.

3. **File access model.** The POC assumes the server has filesystem access to
   the image files (appropriate for a local stdio server).  For future
   HTTP-transport deployments, a file-upload or path-mapping mechanism would be
   needed.

4. **Large file handling.** Some microscopy files are tens or hundreds of
   gigabytes.  Bio-Formats handles this via lazy plane-by-plane reading, but
   operations like `get_intensity_stats` across all planes of a 100 GB file
   could be very slow.  The tool contracts should specify behavior for large
   files (subsampling, warnings, limits).

5. **Concurrency model.** Bio-Formats readers are not thread-safe.  For the
   stdio POC (single client, sequential requests) this is not an issue.  For
   future HTTP/multi-client scenarios, reader pooling or per-request reader
   instantiation will be needed.  Readers are usually relatively inexpensive
   save possibly for buffers.  Leveraging Java 21+ virtual threads by
   default should enable a safe performant solution.

6. **Maven coordinates and group ID.** The actual group ID for Maven Central
   publication needs to be decided (e.g., `org.bioimage`, `io.github.<user>`,
   etc.).

7. **Mill vs Gradle final decision.** Evaluate Mill 1.1's Java support
   concretely with the actual dependency set (Bio-Formats has complex transitive
   dependencies).  Fall back to Gradle if issues arise.
