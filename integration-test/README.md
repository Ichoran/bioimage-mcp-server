# Integration tests

These tests are separate from the project's unit test suite (`mill test`).
They spawn the MCP server as a real subprocess and exercise the JSON-RPC
protocol over stdio — the same way Claude Code or Claude Desktop would
talk to the server.

## Prerequisites

- [JBang](https://www.jbang.dev/) installed and on your `PATH`.
- The assembly jar built: `mill assembly` from the project root.

## Running

From the project root:

```sh
mill assembly && jbang integration-test/SmokeTest.java
```

To use a different assembly jar:

```sh
jbang integration-test/SmokeTest.java /path/to/assembly.jar
```

## What it tests

`SmokeTest.java` runs 9 checks:

1. MCP `initialize` handshake returns correct server name and version.
2. `tools/list` returns all 5 tools.
3. Every tool's JSON schema requires a `path` parameter.
4. `inspect_image` on a missing file returns a tool-level error (not a
   protocol error).
5. `inspect_image` returns structured metadata for a valid OME-TIFF.
6. `get_thumbnail` returns image content (base64 PNG).
7. `get_plane` returns image content.
8. `get_intensity_stats` returns stats JSON with expected fields.
9. `export_to_tiff` creates a non-empty output file.

The test creates a synthetic 32x32 OME-TIFF as a fixture (also via
JBang + the assembly jar), runs the server with that directory
allow-listed, and cleans up afterward.

## Adding tests

Each check is a `check("name", () -> { ... })` block inside
`runTests()`.  Add new checks there.  The `callTool(name, args)`
helper sends a `tools/call` JSON-RPC request and returns the parsed
response.
