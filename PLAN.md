# Implementation Plan

Remove items as they are completed.  Add items when new work is
identified that deserves its own step.  Re-order when priorities change.

**Reference code:** A local clone of the Bio-Formats repository is at
`../../java/bioformats` (i.e. `~/Code/java/bioformats`).  Use it to
look up API details, pixel type constants, metadata accessors, etc.

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
