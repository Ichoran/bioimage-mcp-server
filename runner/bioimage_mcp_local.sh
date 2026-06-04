#!/usr/bin/env bash
# Launch the LOCAL bioimage MCP server from the freshly-built assembly jar,
# bypassing runner/bioimage_mcp.java's published //DEPS (which would shadow
# any not-yet-released classes, e.g. export_to_ngff).
#
# Rebuild the jar with `mill assembly` after changing the code; this script
# always launches whatever the latest build produced.
#
# Register with Claude Code (absolute path, args are forwarded to the server):
#   claude mcp add bioimage-local -s user -- \
#     /abs/path/runner/bioimage_mcp_local.sh --allow /path/to/your/images
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$repo/out/assembly.dest/out.jar"
if [[ ! -f "$jar" ]]; then
  echo "assembly jar not found at $jar — run 'mill assembly' first" >&2
  exit 1
fi
exec java -cp "$jar" lab.kerrr.mcpbio.bioimageserver.BioImageMcpServer "$@"
