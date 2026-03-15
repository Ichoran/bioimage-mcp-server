# Implementation Plan

Remove items as they are completed.  Add items when new work is
identified that deserves its own step.  Re-order when priorities change.

## Already done

- Project skeleton, Mill build, JUnit 5 tests
- `PathAccessControl` — file access security (deny > allow > client roots)
- `CancellableTask` — virtual-thread work runner with timeout and
  interrupt-with-backoff, plus `Handle` for early cancellation


## Phase 1: Data model and reader abstraction

The goal is to define all the types that tools consume and produce, plus
the reader interface, before writing any tool logic.  Everything here is
pure Java with no external dependencies — fully unit-testable.

### 1a. Model records

Define the structured data types in `model/`:
- `ImageMetadata` — dimensions, pixel type, series info
- `ChannelInfo` — name, excitation/emission wavelengths, color
- `PixelSize` — value + unit (µm, nm, etc.)
- `InstrumentInfo` — objective, magnification, NA, immersion
- `IntensityStats` — min/max/mean/stddev/median, histogram, saturation
- `PlaneCoordinate` — (channel, z, timepoint) tuple

These are just records.  No logic beyond maybe validation in compact
constructors.  The exact set will evolve as we implement tools — start
with what `inspect_image` needs and grow from there.

### 1b. Reader abstraction interface

Define `formats/ImageReader` (interface) with methods like:
- `open(Path)` / `close()` — lifecycle
- `getMetadata(series, detailLevel)` → `ImageMetadata`
- `readPlane(series, channel, z, t)` → raw pixel array or buffer
- `getSeriesCount()`, `getPixelType()`, etc.

This is the boundary between tool logic and format-specific code.
Nothing in `tools/` should ever import a Bio-Formats type.

Note: Bio-Formats already abstracts over itself (`IFormatReader` in
the BSD-licensed `formats-api`), and swapping `formats-gpl` for
`formats-bsd` is just a dependency change.  Our abstraction is not for
GPL isolation — it's for testability (fake readers), API shaping
(`IFormatReader` has 100+ methods; our tools need ~10), and the
flexibility to add non-Bio-Formats readers in the future (e.g. direct
OME-ZARR, tifffile via Python).  We are architecting *in* Bio-Formats
for its capabilities, not architecting *out* everything else.

### 1c. Test reader implementation

A fake `ImageReader` that returns synthetic data (e.g. gradient images,
known metadata values).  Lives in `test/`.  This lets us test every tool
thoroughly without needing real microscopy files or Bio-Formats.


## Phase 2: Tools against the fake reader

Build each tool as a pure function: (reader, request) → response.  No
MCP protocol awareness — just Java methods that take structured input and
return structured output.  All tested against the fake reader.

### 2a. `inspect_image`

Metadata only, no pixel data.  Exercises the reader abstraction for
metadata extraction.  Test all three detail levels (summary, standard,
full) and the `omitted_metadata_bytes` calculation.  This is the simplest
tool, so it's the right place to establish the tool implementation
pattern that the others will follow.

### 2b. `get_plane`

Single-channel, single-plane extraction.  First tool that touches pixel
data.  Tests the pixel-reading path, auto-contrast (percentile stretch),
normalization toggle, and PNG encoding.  Keep it simple — one channel,
one plane, grayscale output.

### 2c. `get_intensity_stats`

Statistics over pixel data.  The math (histogram, percentiles,
saturation detection, bit-depth utilization) can be tested with known
synthetic arrays where we can compute expected results by hand.

### 2d. `get_thumbnail`

The most complex pixel tool: Z-projection (mid-slice, max-intensity,
sum), multi-channel compositing with color lookup, and downsampling.
Builds on the pixel-reading infrastructure from `get_plane`.  Test each
projection mode, channel color assignment, and downsampling quality
independently.

### 2e. `export_to_tiff`

Reading + writing.  Needs a writer abstraction (or at least a
Bio-Formats writer behind the same isolation boundary).  Test that
round-tripping preserves metadata and pixel data.  Test subsetting
(channel/z/t ranges).  Test the metadata-loss detection and reporting.

This is last among the tools because it's the most complex and depends
on having the reading side solid.


## Phase 3: Budget and resource constraints

By this point all tools work against the fake reader with unlimited
resources.  Now add the `budget` parameter (max_bytes, timeout_seconds)
and integrate with `CancellableTask`.

- Wire `timeout_seconds` → `CancellableTask` timeout
- Implement byte-counting in the reader to enforce `max_bytes`
  (subsampling, early termination with partial-result reporting)
- Ensure every tool reports clearly when budget limits forced it to
  skip data

This is separate from Phase 2 because budget logic is cross-cutting
and easier to test once the tools themselves are known-good.  But the
tool interfaces should accept budget parameters from the start (with
"unlimited" defaults) so there's no retrofit.


## Phase 4: Bio-Formats reader implementation

Now implement the real `formats/BioFormatsReader`.  At this point, tool
logic is already tested against the fake — this phase is purely about
verifying that Bio-Formats integration works correctly.

- Implement `ImageReader` backed by Bio-Formats `formats-gpl`
- Parse OME-XML into our model records
- Test with real microscopy files (need a small test fixture set:
  at minimum a multi-channel Z-stack TIFF and one proprietary format
  like .czi or .nd2)
- Verify that the fake reader's behavior matches real reader behavior
  closely enough that tool tests remain meaningful

### Test fixtures

We need a small set of real microscopy files for integration tests.
These should be small (a few MB at most) and cover:
- Multi-channel, multi-Z, multi-T
- At least one format beyond TIFF (to verify Bio-Formats adds value)
- Known metadata values we can assert against

OME provides sample files; we may also generate synthetic OME-TIFFs
with known properties.


## Phase 5: MCP server wiring

Connect the tested tools to the MCP SDK transport layer.

- Register each tool with `McpSyncServer` (or async variant)
- JSON schema generation for tool input parameters
- Map `CallToolRequest` → tool method calls → `CallToolResult`
- Wire `PathAccessControl` checks into every tool that takes a path
- Wire `ProgressNotification` into long-running tools
- Handle `listRoots` from the client to populate client roots

This should be mostly mechanical plumbing.  If the tools are well-tested
pure functions, surprises here are limited to serialization edge cases
and transport behavior.

### Integration smoke tests

Stand up a real `StdioServerTransportProvider`, send JSON-RPC messages
to stdin, verify responses on stdout.  This catches serialization issues
and protocol misunderstandings without needing a full MCP client.


## Phase 6: Runner and end-to-end

- Create the JBang runner file (`runner/bioimage-mcp.java`)
- Wire allow/deny path configuration into the runner
- Test with Claude Code and/or Claude Desktop as actual MCP clients
- Verify image display (base64 PNG inline rendering)
- Verify progress notifications appear in the client
- Verify error messages are clear and actionable

This is where we discover the gap between "what we thought the transport
would do" and "what it actually does."  By this point, the gap should be
small.
