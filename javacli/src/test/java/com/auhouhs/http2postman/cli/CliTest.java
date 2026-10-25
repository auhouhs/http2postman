/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.cli;

import com.auhouhs.http2postman.validation.CollectionValidator;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.auhouhs.http2postman.TestSupport.FIXTURES;
import static com.auhouhs.http2postman.TestSupport.parseJson;
import static com.auhouhs.http2postman.TestSupport.texts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI tests: options, reports, env defaults, split output and invariants. */
class CliTest {

    @TempDir
    Path tmp;

    private record Run(int code, String out, String err) {
    }

    private static Run run(String... argv) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            int code = Cli.run(argv, out, err);
            out.flush();
            err.flush();
            return new Run(code, outBytes.toString(StandardCharsets.UTF_8),
                    errBytes.toString(StandardCharsets.UTF_8));
        }
    }

    private static void write(Path path, String content) throws Exception {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static JsonNode readJson(Path path) throws Exception {
        return parseJson(Files.readString(path, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // basics
    // ------------------------------------------------------------------

    @Test
    void fileInputDefaultOutput() throws Exception {
        Path src = tmp.resolve("one.http");
        write(src, "### R\nGET http://api.example.com/x\n");
        Run result = run(src.toString());
        assertEquals(0, result.code());
        assertEquals("", result.err());
        Path outPath = tmp.resolve("postman_collection.json");
        assertTrue(Files.exists(outPath));
        JsonNode collection = readJson(outPath);
        assertEquals("One", collection.get("info").get("name").asText());
        assertTrue(result.out().contains("Requests: 1"));
        assertTrue(result.out().contains("Generated:"));
    }

    @Test
    void outputFlagAndQuiet() throws Exception {
        Path src = tmp.resolve("one.http");
        Path outPath = tmp.resolve("custom.json");
        write(src, "### R\nGET http://api.example.com/x\n");
        Run result = run(src.toString(), "-o", outPath.toString(), "--quiet");
        assertEquals(0, result.code());
        assertEquals("", result.out());
        assertTrue(Files.exists(outPath));
    }

    @Test
    void nameOverride() throws Exception {
        Path src = tmp.resolve("one.http");
        Path outPath = tmp.resolve("o.json");
        write(src, "### R\nGET http://api.example.com/x\n");
        assertEquals(0, run(src.toString(), "-o", outPath.toString(), "--name", "Custom").code());
        assertEquals("Custom", readJson(outPath).get("info").get("name").asText());
    }

    @Test
    void directoryScanIncludesRest() throws Exception {
        Path sub = tmp.resolve("sub");
        Files.createDirectories(sub);
        write(tmp.resolve("a.http"), "### A\nGET http://api.example.com/a\n");
        write(sub.resolve("b.rest"), "### B\nGET http://api.example.com/b\n");
        Path outPath = tmp.resolve("o.json");
        Run result = run(tmp.toString(), "-o", outPath.toString());
        assertEquals(0, result.code());
        assertTrue(result.out().contains("Requests: 2"));
        JsonNode collection = readJson(outPath);
        assertEquals(List.of("A", "B"), itemNames(collection.get("item")));
    }

    @Test
    void multiInputFirstBasenameNaming() throws Exception {
        Path first = tmp.resolve("alpha.http");
        Path second = tmp.resolve("beta.http");
        write(first, "### A\nGET http://api.example.com/a\n");
        write(second, "### B\nGET http://api.example.com/b\n");
        Path outPath = tmp.resolve("o.json");
        assertEquals(0, run(first.toString(), second.toString(), "-o", outPath.toString()).code());
        JsonNode collection = readJson(outPath);
        assertEquals("Alpha", collection.get("info").get("name").asText());
        assertEquals(List.of("Alpha", "Beta"), itemNames(collection.get("item")));
    }

    @Test
    void exitCodes() {
        Run missing = run(tmp.resolve("nonexistent-http2postman-test.http").toString());
        assertEquals(1, missing.code());
        assertTrue(missing.err().contains("error"));

        Run noArgs = run();
        assertEquals(2, noArgs.code());
    }

    @Test
    void wrongExtensionIsUsageError() throws Exception {
        Path bad = tmp.resolve("x.json");
        write(bad, "{}");
        assertEquals(2, run(bad.toString()).code());
    }

    // ------------------------------------------------------------------
    // report
    // ------------------------------------------------------------------

    @Test
    void reportContents() throws Exception {
        Path src = tmp.resolve("r.http");
        Path outPath = tmp.resolve("o.json");
        write(src, "@v = 1\n### R\nGET http://api.example.com/x\n\nbody\n> {% client.log('x'); %}\n");
        Run result = run(src.toString(), "-o", outPath.toString());
        assertEquals(0, result.code());
        assertTrue(result.out().contains("Generated:"));
        assertTrue(result.out().contains("Requests: 1"));
        assertTrue(result.out().contains("Variables: 1"));
        assertTrue(result.out().contains("Scripts: 1"));
        assertFalse(result.out().contains("response scripts at"));
    }

    @Test
    void zeroRequestsWarningAndValidCollection() throws Exception {
        Path src = tmp.resolve("empty.http");
        Path outPath = tmp.resolve("o.json");
        write(src, "@v = 1\n");
        Run result = run(src.toString(), "-o", outPath.toString());
        assertEquals(0, result.code());
        assertTrue(result.out().contains("no requests parsed"));
        assertEquals("[]", readJson(outPath).get("item").toString());
    }

    @Test
    void unmappedDynamicVarReported() throws Exception {
        Path src = tmp.resolve("d.http");
        Path outPath = tmp.resolve("o.json");
        write(src, "### R\nGET http://api.example.com/x?k=$weird\n");
        Run result = run(src.toString(), "-o", outPath.toString());
        assertEquals(0, result.code());
        assertTrue(result.out().contains("unmapped dynamic vars"));
    }

    @Test
    void scriptsCountAndNoScriptWarnings() throws Exception {
        Path src = tmp.resolve("s.http");
        Path outPath = tmp.resolve("o.json");
        write(src, "### R\n"
                + "< {%\n"
                + "    request.variables.set(\"k\", \"v\");\n"
                + "%}\n"
                + "POST http://api.example.com/x\n\n"
                + "body\n"
                + "> {% client.log('a'); %}\n"
                + "> {% client.log('b'); %}\n");
        Run result = run(src.toString(), "-o", outPath.toString());
        assertEquals(0, result.code());
        assertTrue(result.out().contains("Scripts: 3"));
        assertFalse(result.out().contains("response scripts at"));
        assertFalse(result.out().contains("pre_request_script"));

        JsonNode events = readJson(outPath).get("item").get(0).get("event");
        assertEquals(2, events.size());
        assertEquals("prerequest", events.get(0).get("listen").asText());
        assertEquals("test", events.get(1).get("listen").asText());
        assertTrue(texts(events.get(0).get("script").get("exec")).stream()
                .anyMatch(line -> line.contains("pm.variables.set")));
        assertTrue(texts(events.get(1).get("script").get("exec")).stream()
                .anyMatch(line -> line.contains("console.log")));
    }

    @Test
    void scriptsCountOnSplitPath() throws Exception {
        Path a = tmp.resolve("big-a.http");
        Path b = tmp.resolve("big-b.http");
        String body = "x".repeat(1_300_000);
        write(a, "### A\nPOST http://api.example.com/big\n\n" + body + "\n> {% client.log('a'); %}\n");
        write(b, "### B\nPOST http://api.example.com/big\n\n" + body + "\n> {% client.log('b'); %}\n");
        Run result = run(a.toString(), b.toString(), "-o", tmp.resolve("out.json").toString());
        assertEquals(0, result.code());
        assertTrue(result.out().contains("Scripts: 1"));
    }

    @Test
    void websocketReportedUnderUnsupported() throws Exception {
        Path src = tmp.resolve("ws.http");
        Path outPath = tmp.resolve("o.json");
        write(src, "WEBSOCKET wss://echo.example.com/socket\nX-Foo: bar\n\n### R\nGET http://api.example.com/x\n");
        Run result = run(src.toString(), "-o", outPath.toString());
        assertEquals(0, result.code());
        assertTrue(result.out().contains("websocket requests at"));
        assertTrue(result.out().contains("Requests: 1"));
    }

    // ------------------------------------------------------------------
    // --prefer-env
    // ------------------------------------------------------------------

    private void setupEnvProject() throws Exception {
        write(tmp.resolve("http-client.env.json"),
                "{\"dev\": {\"baseUrl\": \"https://dev.example.com\", \"apiKey\": \"dev-key\", \"port\": 8080},"
                        + " \"prod\": {\"baseUrl\": \"https://prod.example.com\"}}");
        write(tmp.resolve("http-client.private.env.json"),
                "{\"dev\": {\"secret\": \"s3cret\", \"apiKey\": \"private-dev-key\"}}");
        write(tmp.resolve("sample.http"),
                "@baseUrl = https://file.example.com\n### R\nGET {{baseUrl}}/users\n"
                        + "X-Key: {{apiKey}}\nX-Secret: {{secret}}\n");
    }

    @Test
    void preferEnvUsesEnvValuesAsDefaults() throws Exception {
        setupEnvProject();
        Path outPath = tmp.resolve("o.json");
        Run result = run(tmp.resolve("sample.http").toString(), "-o", outPath.toString(),
                "--prefer-env", "dev");
        assertEquals(0, result.code());
        assertTrue(result.out().contains("Env: dev"));
        Map<String, String> variables = new LinkedHashMap<>();
        for (JsonNode variable : readJson(outPath).get("variable")) {
            variables.put(variable.get("key").asText(), variable.get("value").asText());
        }
        assertEquals("https://dev.example.com", variables.get("baseUrl"));
        assertEquals("private-dev-key", variables.get("apiKey"));
        assertEquals("s3cret", variables.get("secret"));
        assertEquals("8080", variables.get("port"));
    }

    @Test
    void missingEnvNameExits1() throws Exception {
        setupEnvProject();
        Path outPath = tmp.resolve("o.json");
        Run result = run(tmp.resolve("sample.http").toString(), "-o", outPath.toString(),
                "--prefer-env", "staging");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("environment 'staging' not found"));
        assertFalse(Files.exists(outPath));
    }

    @Test
    void missingEnvFileExits1() throws Exception {
        write(tmp.resolve("sample.http"), "### R\nGET http://x.test/a\n");
        Run result = run(tmp.resolve("sample.http").toString(), "--prefer-env", "dev");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("http-client.env.json not found"));
    }

    // ------------------------------------------------------------------
    // split output
    // ------------------------------------------------------------------

    private void bigFile(Path path, String label, int size) throws Exception {
        write(path, "### " + label + "\nPOST http://api.example.com/big\n\n" + "x".repeat(size) + "\n");
    }

    @Test
    void multiInputSplit() throws Exception {
        Path a = tmp.resolve("big-a.http");
        Path b = tmp.resolve("big-b.http");
        bigFile(a, "A", 1_300_000);
        bigFile(b, "B", 1_300_000);
        Path outPath = tmp.resolve("out.json");
        Run result = run(a.toString(), b.toString(), "-o", outPath.toString());
        assertEquals(0, result.code());
        assertFalse(Files.exists(outPath));
        for (String stem : List.of("out-big-a.json", "out-big-b.json")) {
            Path splitPath = tmp.resolve(stem);
            assertTrue(Files.exists(splitPath), stem);
            assertFalse(readJson(splitPath).get("item").isEmpty());
        }
        assertTrue(result.out().toLowerCase().contains("split"));
    }

    @Test
    void singleInputOversizeEmitsWithWarning() throws Exception {
        Path a = tmp.resolve("huge.http");
        bigFile(a, "Huge", 2_500_000);
        Path outPath = tmp.resolve("out.json");
        Run result = run(a.toString(), "-o", outPath.toString());
        assertEquals(0, result.code());
        assertTrue(Files.exists(outPath));
        assertTrue(result.out().contains("exceeds 2 MB"));
    }

    // ------------------------------------------------------------------
    // invariants
    // ------------------------------------------------------------------

    @Test
    void sourceUnchanged() throws Exception {
        Path src = tmp.resolve("in.http");
        String content = "### R\nGET http://api.example.com/x\n";
        write(src, content);
        long modified = Files.getLastModifiedTime(src).toMillis();
        long size = Files.size(src);
        run(src.toString(), "-o", tmp.resolve("o.json").toString());
        assertEquals(content, Files.readString(src, StandardCharsets.UTF_8));
        assertEquals(modified, Files.getLastModifiedTime(src).toMillis());
        assertEquals(size, Files.size(src));
    }

    @Test
    void atomicNoPartialFileOnUnwritableOutputDir() throws Exception {
        Path src = tmp.resolve("in.http");
        write(src, "### R\nGET http://api.example.com/x\n");
        Path outPath = tmp.resolve("missing-dir").resolve("o.json");
        Run result = run(src.toString(), "-o", outPath.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("error"));
        assertFalse(Files.exists(tmp.resolve("missing-dir")));
    }

    @Test
    void nonUtf8Input() throws Exception {
        Path src = tmp.resolve("bad.http");
        Files.write(src, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0x81});
        Path outPath = tmp.resolve("o.json");
        Run result = run(src.toString(), "-o", outPath.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("UTF-8"));
        assertFalse(Files.exists(outPath));
    }

    // ------------------------------------------------------------------
    // end to end
    // ------------------------------------------------------------------

    @Test
    void fixtureDirectoryFullRun() throws Exception {
        Path outPath = tmp.resolve("o.json");
        Run result = run(FIXTURES.toString(), "-o", outPath.toString());
        assertEquals(0, result.code(), result.err());
        JsonNode collection = readJson(outPath);
        assertEquals(List.of(), CollectionValidator.validate(collection).findings());
        assertTrue(collection.get("item").size() > 0);
    }

    @Test
    void idempotentReruns() throws Exception {
        Path first = tmp.resolve("run1.json");
        Path second = tmp.resolve("run2.json");
        assertEquals(0, run(FIXTURES.toString(), "-o", first.toString()).code());
        assertEquals(0, run(FIXTURES.toString(), "-o", second.toString()).code());
        assertEquals(Files.readString(first, StandardCharsets.UTF_8),
                Files.readString(second, StandardCharsets.UTF_8));
    }

    private static List<String> itemNames(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode node : array) {
            out.add(node.get("name").asText());
        }
        return out;
    }
}
