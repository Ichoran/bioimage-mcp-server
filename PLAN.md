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


## Phase 3: Budget and resource constraints

Tools already accept timeout and byte-budget parameters with sensible
defaults, and run inside `CancellableTask`.  `get_intensity_stats` has
adaptive reading with rate estimation (done).  This phase is for any
remaining budget work that emerges from building the later tools —
e.g. adaptive reading for `get_thumbnail`, or partial-result reporting
when budgets force data to be skipped.


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
