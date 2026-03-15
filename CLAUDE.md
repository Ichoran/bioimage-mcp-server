# Purpose and outlook

This project contains scientific software: a MCP server that provides
Bio-Formats reader / metadata capability for AI agents.

Because this is scientific software, **every error that is caught and
ignored must be justified**.  It is very important that we not give users a
false confidence that information has been incorporated properly when it has
not; this can lead to incorrect conclusions.

For example, if one can use either of two TIFF readers, and one fails to
load, it is fine to fail over to the other one.  However, if we expect to
get summary data from five images and only four load successfully it is NOT
okay to make a "best effort" and return summary data incorporating only the
four images.  The fifth may have been essential--we don't know.  The error
must be propagated.

This point is so important that it bears repeating.  Every error that is
caught and ignored must be justified!  It is very important that we not give
users a false confidence that information has been incorporated properly
when it has not.

Although this is a tool for AI agents to use, they cannot properly reason
about what to do if the tool itself fails to return all potentially relevant
errors.

# Important documents.

It is important to be familiar with @README.md and @DESIGN.md since these
documents explain the project in the greatest detail.

@PLAN.md contains the current implementation plan.  Check it at the start
of each session to see what to work on next, and update it as work is
completed or priorities change.

# Build system

- **Mill 1.1** is the build tool.
- The build definition is in `build.mill.yaml` (YAML format, not Scala).
- Main sources are in `src/server/` with package `lab.kerrr.mcpbio.bioimageserver`.
- The JVM target is Java 21.
- Bio-Formats is fetched from the OME Maven repository at
  `https://artifacts.openmicroscopy.org/artifactory/maven/`.
- Tests use JUnit 5 (Jupiter), configured in `test/package.mill.yaml`.
- Test sources are in `test/src/server/` with the same package as main sources.
- Run tests with `mill test`.

# MCP Java SDK

- The dependency is `io.modelcontextprotocol.sdk:mcp:1.1.0`.
- Source code: https://github.com/modelcontextprotocol/java-sdk
- Documentation: https://java.sdk.modelcontextprotocol.io/ (redirected from
  `https://modelcontextprotocol.github.io/java-sdk/`)
- The SDK uses **Project Reactor** (`Mono`/`Flux`) internally, with a
  synchronous facade (`McpSyncServer`) layered on top.
- This project does **not** use the Spring Boot starters — it is plain Java.
- When looking up SDK source from the local build cache, check
  `mill show compile` or coursier cache paths for the resolved jar.
