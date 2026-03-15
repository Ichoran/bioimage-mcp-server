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


## Phase 2: Tools against the fake reader

Build each tool as a pure function: (reader, request) → response.  No
MCP protocol awareness — just Java methods that take structured input and
return structured output.  All tested against the fake reader.

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

Tools already accept timeout and byte-budget parameters with sensible
defaults, and run inside `CancellableTask`.  This phase is for any
remaining budget work that emerges from building the later tools —
e.g. subsampling strategies for `get_intensity_stats` on huge files,
or partial-result reporting when budgets force data to be skipped.


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
