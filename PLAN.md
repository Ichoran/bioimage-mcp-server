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
