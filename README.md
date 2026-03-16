# bioimage-mcp-server

An MCP server that gives LLM agents access to microscopy image data.
Reads 150+ file formats (CZI, ND2, LIF, OME-TIFF, and many more) via
[Bio-Formats](https://www.openmicroscopy.org/bio-formats/) and exposes
five tools for inspecting metadata, generating visual previews,
computing intensity statistics, and converting to open formats.

Hand your agent a `.czi` and ask "what channels were acquired?" or
"is this image saturated?" — no desktop application or one-off script
required.


## Quick start

### Prerequisites

- [JBang](https://www.jbang.dev/) (installs and manages the JVM for you)

### Claude Code

```sh
claude mcp add bioimage-mcp \
  -- jbang https://github.com/ichoran/bioimage-mcp-server/blob/main/runner/bioimage_mcp.java
```

Or from a local clone:

```sh
claude mcp add bioimage-mcp \
  -- jbang runner/bioimage_mcp.java
```

### Claude Desktop

Add to your Claude Desktop configuration (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "bioimage-mcp": {
      "command": "jbang",
      "args": ["runner/bioimage_mcp.java"]
    }
  }
}
```

### File access

By default the server only accesses files under directories declared by
the MCP client (client roots).  To grant access to additional paths, or
to deny access to sensitive directories, edit the runner file:

```java
BioImageMcpServer.builder()
    .allow("/data/microscopy")
    .allow("/shared/lab-images")
    .deny("/data/microscopy/private")
    .build()
    .run(args);
```

Deny rules always win.  See DESIGN.md §5 for details.


## Tools

| Tool | Returns | Purpose |
|------|---------|---------|
| `inspect_image` | JSON | Dimensions, pixel type, physical sizes, channels, instrument info |
| `get_thumbnail` | PNG | RGB composite preview with Z-projection and channel compositing |
| `get_plane` | PNG | Single-channel grayscale plane at full resolution |
| `get_intensity_stats` | JSON | Min/max/mean/stddev/median, histogram, saturation warnings |
| `export_to_tiff` | JSON | Convert to OME-TIFF with optional subsetting |

All tools accept an absolute file path and handle large files safely
(adaptive subsampling, time budgets).  Errors are returned as
structured messages, never silently swallowed — see DESIGN.md for the
rationale.

### Typical workflow

1. `inspect_image` — learn what's in the file (start with `summary` detail)
2. `get_thumbnail` — quick visual overview
3. `get_plane` / `get_intensity_stats` — detailed investigation
4. `export_to_tiff` — convert for downstream tools (FIJI, napari, etc.)


## Building from source

### Prerequisites

- [Mill](https://mill-build.org/) 1.1+
- JDK 21+

### Commands

```sh
mill test                # run all tests (365 unit + integration)
mill assembly            # build fat jar
mill run                 # run the server directly via Mill
mill publishLocal        # publish to ~/.ivy2/local for local testing
```

### Development with JBang

To test the runner against a local build (without publishing to Maven
Central):

```sh
mill assembly
jbang --cp "$(mill show assembly | tr -d '"')" runner/bioimage_mcp.java
```

### Integration tests

Spawns the server as a subprocess and exercises the MCP protocol over
stdio:

```sh
mill assembly && jbang integration-test/SmokeTest.java
```

See `integration-test/README.md` for details.


## Project structure

```
src/server/          Main sources (Java 21, package lab.kerrr.mcpbio.bioimageserver)
test/src/server/     Unit tests (JUnit 5)
test/fixtures/       Test data (downloaded on demand, gitignored)
runner/              JBang entry point for end users
integration-test/    End-to-end MCP protocol tests
```

Bio-Formats API usage is confined to `BioFormatsReader.java` and
`BioFormatsWriter.java`.  Everything else depends only on the
`ImageReader` / `ImageWriter` interfaces and model records.


## License

GPL-3.0 — see [LICENSE](LICENSE).

Bio-Formats (`ome:formats-gpl`) is GPL-2.0, which is compatible with
GPLv3.
