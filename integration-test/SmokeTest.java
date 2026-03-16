///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS tools.jackson.core:jackson-databind:3.0.3

// =================================================================
// MCP transport smoke test
//
// Spawns the bioimage-mcp server as a subprocess and exercises the
// JSON-RPC/MCP protocol over stdio.  Verifies that initialization,
// tool listing, and all five tool calls work end-to-end.
//
// See README.md in this directory for usage instructions.
// =================================================================

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SmokeTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static int nextId = 1;
    private static int passed = 0;
    private static int failed = 0;

    private static Path assemblyJar;
    private static Process serverProcess;
    private static OutputStream toServer;
    private static ByteArrayOutputStream stderrCapture;
    private static BufferedReader fromServer;

    public static void main(String[] args) throws Exception {
        assemblyJar = findJar(args);
        var tempDir = Files.createTempDirectory("bioimage-smoke-");

        try {
            startServer(tempDir);
            runTests(tempDir);
        } finally {
            stopServer();
            deleteRecursive(tempDir);
        }

        System.out.println();
        System.out.println("=== Results: " + passed + " passed, "
                + failed + " failed ===");
        System.exit(failed > 0 ? 1 : 0);
    }

    // ================================================================
    // Test cases
    // ================================================================

    private static void runTests(Path tempDir) throws Exception {
        // --- Initialize ---
        var initResponse = initialize();
        check("initialize returns server info",
                () -> {
                    var result = mapGet(initResponse, "result");
                    var info = mapGet(result, "serverInfo");
                    assertEqual("bioimage-mcp", info.get("name"));
                    assertEqual("0.1.0", info.get("version"));
                });

        // --- Tools list ---
        var toolsResponse = callMethod("tools/list", Map.of());
        check("tools/list returns 5 tools",
                () -> {
                    var result = mapGet(toolsResponse, "result");
                    var tools = listGet(result, "tools");
                    assertEqual(5, tools.size());
                    var names = tools.stream()
                            .map(t -> (String) ((Map<?, ?>) t).get("name"))
                            .sorted().toList();
                    assertEqual(List.of(
                            "export_to_tiff", "get_intensity_stats",
                            "get_plane", "get_thumbnail", "inspect_image"),
                            names);
                });

        // --- Tool schemas have required fields ---
        check("each tool has input schema with required 'path'",
                () -> {
                    var tools = listGet(
                            mapGet(toolsResponse, "result"), "tools");
                    for (var raw : tools) {
                        @SuppressWarnings("unchecked")
                        var tool = (Map<String, Object>) raw;
                        var schema = mapGet(tool, "inputSchema");
                        var required = listGet(schema, "required");
                        assertTrue(required.contains("path"),
                                tool.get("name") + " should require 'path'");
                    }
                });

        // --- inspect_image on missing file ---
        var missingResult = callTool("inspect_image",
                Map.of("path", "/nonexistent/file.tif"));
        check("inspect_image on missing file returns tool error",
                () -> {
                    var result = mapGet(missingResult, "result");
                    assertEqual(true, result.get("isError"));
                });

        // --- Create a test OME-TIFF for the remaining tests ---
        var testFile = createTestOmeTiff(tempDir);

        // --- inspect_image on real file ---
        var inspectResult = callTool("inspect_image",
                Map.of("path", testFile.toString(), "detail", "summary"));
        check("inspect_image returns metadata for valid file",
                () -> {
                    var result = mapGet(inspectResult, "result");
                    assertNotError(result);
                    var content = firstTextContent(result);
                    assertTrue(content.contains("sizeX"),
                            "should contain sizeX");
                    assertTrue(content.contains("sizeY"),
                            "should contain sizeY");
                });

        // --- get_thumbnail ---
        var thumbResult = callTool("get_thumbnail",
                Map.of("path", testFile.toString(), "max_size", 32));
        check("get_thumbnail returns image content",
                () -> {
                    var result = mapGet(thumbResult, "result");
                    assertNotError(result);
                    var content = listGet(result, "content");
                    var hasImage = content.stream()
                            .anyMatch(c -> "image".equals(
                                    ((Map<?, ?>) c).get("type")));
                    assertTrue(hasImage, "should include image content");
                });

        // --- get_plane ---
        var planeResult = callTool("get_plane",
                Map.of("path", testFile.toString(), "channel", 0));
        check("get_plane returns image content",
                () -> {
                    var result = mapGet(planeResult, "result");
                    assertNotError(result);
                    var content = listGet(result, "content");
                    var hasImage = content.stream()
                            .anyMatch(c -> "image".equals(
                                    ((Map<?, ?>) c).get("type")));
                    assertTrue(hasImage, "should include image content");
                });

        // --- get_intensity_stats ---
        var statsResult = callTool("get_intensity_stats",
                Map.of("path", testFile.toString(),
                        "channel", 0, "z_slice", 0, "timepoint", 0));
        check("get_intensity_stats returns stats JSON",
                () -> {
                    var result = mapGet(statsResult, "result");
                    assertNotError(result);
                    var text = firstTextContent(result);
                    assertTrue(text.contains("pixel_type"),
                            "should contain pixel_type");
                    assertTrue(text.contains("mean"),
                            "should contain mean");
                });

        // --- export_to_tiff ---
        var outputFile = tempDir.resolve("exported.ome.tiff");
        var exportResult = callTool("export_to_tiff",
                Map.of("path", testFile.toString(),
                        "output_path", outputFile.toString()));
        check("export_to_tiff creates output file",
                () -> {
                    var result = mapGet(exportResult, "result");
                    assertNotError(result);
                    assertTrue(Files.exists(outputFile),
                            "output file should exist");
                    assertTrue(Files.size(outputFile) > 0,
                            "output file should not be empty");
                });
    }

    // ================================================================
    // Server lifecycle
    // ================================================================

    private static Path findJar(String[] args) {
        Path jar;
        if (args.length > 0) {
            jar = Path.of(args[0]);
        } else {
            jar = Path.of("out/assembly.dest/out.jar");
        }
        if (!Files.exists(jar)) {
            System.err.println("ERROR: Assembly jar not found at " + jar);
            System.err.println("Run 'mill assembly' first, or pass the jar "
                    + "path as an argument.");
            System.exit(2);
        }
        return jar;
    }

    /**
     * Start the server via jbang, using a temp launcher script that
     * configures the builder with the allow-listed temp directory.
     */
    private static void startServer(Path allowDir) throws Exception {
        var launcherDir = Files.createTempDirectory("bioimage-launcher-");
        var launcherSrc = launcherDir.resolve("ServerLauncher.java");
        Files.writeString(launcherSrc, """
                //JAVA 21
                import lab.kerrr.mcpbio.bioimageserver.BioImageMcpServer;
                public class ServerLauncher {
                    public static void main(String[] args) {
                        BioImageMcpServer.builder()
                            .allow(args[0])
                            .build()
                            .run(new String[0]);
                    }
                }
                """);

        serverProcess = new ProcessBuilder(
                "jbang", "--quiet", "--cp",
                assemblyJar.toAbsolutePath().toString(),
                launcherSrc.toAbsolutePath().toString(),
                allowDir.toAbsolutePath().toString())
                .redirectErrorStream(false)
                .start();

        toServer = serverProcess.getOutputStream();
        fromServer = new BufferedReader(
                new InputStreamReader(serverProcess.getInputStream(),
                        StandardCharsets.UTF_8));

        // Capture stderr in background for diagnostics on failure
        var stderr = serverProcess.getErrorStream();
        stderrCapture = new ByteArrayOutputStream();
        Thread.ofVirtual().start(() -> {
            try {
                stderr.transferTo(stderrCapture);
            } catch (IOException e) { /* ignore */ }
        });
    }

    private static void stopServer() throws Exception {
        if (serverProcess != null && serverProcess.isAlive()) {
            toServer.close();
            if (!serverProcess.waitFor(5, TimeUnit.SECONDS)) {
                serverProcess.destroyForcibly();
            }
        }
    }

    // ================================================================
    // JSON-RPC communication
    // ================================================================

    private static void send(Map<String, Object> message) throws IOException {
        byte[] json = MAPPER.writeValueAsBytes(message);
        toServer.write(json);
        toServer.write('\n');
        toServer.flush();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> receive() throws IOException {
        var line = fromServer.readLine();
        if (line == null) {
            var stderrText = stderrCapture != null
                    ? stderrCapture.toString(StandardCharsets.UTF_8) : "";
            throw new IOException("Server closed stdout unexpectedly."
                    + (stderrText.isEmpty() ? "" : "\nstderr:\n" + stderrText));
        }
        return MAPPER.readValue(line, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> initialize() throws IOException {
        var params = new LinkedHashMap<String, Object>();
        params.put("protocolVersion", "2025-03-26");
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of(
                "name", "smoke-test", "version", "0.0.1"));

        send(Map.of(
                "jsonrpc", "2.0",
                "id", nextId++,
                "method", "initialize",
                "params", params));
        var response = receive();

        // Send initialized notification (no response expected)
        send(Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized"));

        return response;
    }

    private static Map<String, Object> callMethod(
            String method, Map<String, Object> params) throws IOException {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", nextId++);
        msg.put("method", method);
        if (params != null && !params.isEmpty()) {
            msg.put("params", params);
        }
        send(msg);
        return receive();
    }

    private static Map<String, Object> callTool(
            String name, Map<String, Object> arguments) throws IOException {
        return callMethod("tools/call", Map.of(
                "name", name,
                "arguments", arguments));
    }

    // ================================================================
    // Test file creation (using Bio-Formats via the assembly jar)
    // ================================================================

    /**
     * Create a minimal OME-TIFF by running a jbang helper script
     * against the assembly jar.  This avoids needing Bio-Formats on
     * the smoke test's own classpath.
     */
    private static Path createTestOmeTiff(Path dir) throws Exception {
        var file = dir.resolve("test.ome.tiff");

        var helperDir = Files.createTempDirectory("bioimage-fixture-");
        var helperSrc = helperDir.resolve("CreateTestFile.java");
        Files.writeString(helperSrc, """
                //JAVA 21
                import loci.formats.out.OMETiffWriter;
                import loci.formats.services.OMEXMLService;
                import loci.common.services.ServiceFactory;

                public class CreateTestFile {
                    public static void main(String[] args) throws Exception {
                        String path = args[0];
                        String xml = "<?xml version=\\"1.0\\" encoding=\\"UTF-8\\"?>"
                            + "<OME xmlns=\\"http://www.openmicroscopy.org/Schemas/OME/2016-06\\">"
                            + "<Image ID=\\"Image:0\\" Name=\\"test\\">"
                            + "<Pixels ID=\\"Pixels:0\\" DimensionOrder=\\"XYCZT\\""
                            + " SizeX=\\"32\\" SizeY=\\"32\\" SizeZ=\\"1\\""
                            + " SizeC=\\"1\\" SizeT=\\"1\\" Type=\\"uint8\\""
                            + " BigEndian=\\"false\\">"
                            + "<Channel ID=\\"Channel:0:0\\" SamplesPerPixel=\\"1\\"/>"
                            + "<TiffData FirstC=\\"0\\" FirstZ=\\"0\\" FirstT=\\"0\\""
                            + " PlaneCount=\\"1\\" IFD=\\"0\\"/>"
                            + "</Pixels></Image></OME>";

                        var sf = new ServiceFactory();
                        var svc = sf.getInstance(OMEXMLService.class);
                        var meta = svc.createOMEXMLMetadata(xml);

                        var writer = new OMETiffWriter();
                        writer.setBigTiff(true);
                        writer.setMetadataRetrieve(meta);
                        writer.setId(path);

                        byte[] plane = new byte[32 * 32];
                        for (int i = 0; i < plane.length; i++)
                            plane[i] = (byte) (i % 200 + 10);
                        writer.saveBytes(0, plane);
                        writer.close();
                    }
                }
                """);

        var proc = new ProcessBuilder(
                "jbang", "--quiet", "--cp",
                assemblyJar.toAbsolutePath().toString(),
                helperSrc.toAbsolutePath().toString(),
                file.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        var output = new String(proc.getInputStream().readAllBytes());
        if (!proc.waitFor(60, TimeUnit.SECONDS) || proc.exitValue() != 0) {
            throw new IOException(
                    "Failed to create test fixture:\n" + output);
        }

        if (!Files.exists(file) || Files.size(file) == 0) {
            throw new IOException("Test fixture was not created: " + file);
        }

        return file;
    }

    // ================================================================
    // Check framework
    // ================================================================

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void check(String name, ThrowingRunnable body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Exception | AssertionError e) {
            failed++;
            System.out.println("  FAIL  " + name + ": " + e.getMessage());
        }
    }

    // ================================================================
    // Assertion helpers
    // ================================================================

    private static void assertEqual(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                    "expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapGet(
            Map<String, Object> map, String key) {
        var val = map.get(key);
        if (val == null) {
            throw new AssertionError("missing key: " + key
                    + " in " + map.keySet());
        }
        return (Map<String, Object>) val;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listGet(
            Map<String, Object> map, String key) {
        var val = map.get(key);
        if (val == null) {
            throw new AssertionError("missing key: " + key);
        }
        return (List<Object>) val;
    }

    private static void assertNotError(Map<String, Object> result) {
        if (Boolean.TRUE.equals(result.get("isError"))) {
            var content = result.get("content");
            throw new AssertionError("tool returned error: " + content);
        }
    }

    @SuppressWarnings("unchecked")
    private static String firstTextContent(Map<String, Object> result) {
        var content = listGet(result, "content");
        for (var item : content) {
            var m = (Map<String, Object>) item;
            if ("text".equals(m.get("type"))) {
                return (String) m.get("text");
            }
        }
        throw new AssertionError("no text content found");
    }

    // ================================================================
    // Utilities
    // ================================================================

    private static void deleteRecursive(Path dir) {
        try {
            try (var walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); }
                            catch (IOException e) { /* best effort */ }
                        });
            }
        } catch (IOException e) { /* best effort */ }
    }
}
