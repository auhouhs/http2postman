/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.auhouhs.http2postman.TestSupport.pairs;
import static com.auhouhs.http2postman.TestSupport.parseJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Programmatic entry point: the plugin-facing counterpart of the CLI. */
class PostmanConversionTest {

    @TempDir
    Path tmp;

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    @Test
    void singleFileWithDerivedName() throws Exception {
        Path source = tmp.resolve("src/my_api.http");
        Path output = tmp.resolve("out/postman_collection.json");
        write(source, "@baseUrl = http://api.example.com\n### List\nGET {{baseUrl}}/users\n");

        ConversionResult result = PostmanConversion.convert(new ConversionRequest(
                List.of(source), output, null, null, null));

        assertEquals(output.toAbsolutePath(), result.outputFile());
        assertEquals("My Api", result.collectionName());
        assertEquals(1, result.requests());
        assertEquals(1, result.variables());
        assertFalse(result.hasWarnings());
        assertTrue(result.sizeBytes() > 0);
        JsonNode collection = parseJson(Files.readString(output, StandardCharsets.UTF_8));
        assertEquals("My Api", collection.get("info").get("name").asText());
        assertEquals(List.of("baseUrl=http://api.example.com"), pairs(collection.get("variable")));
    }

    @Test
    void explicitNameAndEnvironmentDefaults() throws Exception {
        Path source = tmp.resolve("api.http");
        write(source, "### R\nGET {{baseUrl}}/x\n");
        write(tmp.resolve("http-client.env.json"), "{\"dev\": {\"baseUrl\": \"https://dev.example.com\"}}");
        Path output = tmp.resolve("c.json");

        ConversionResult result = PostmanConversion.convert(new ConversionRequest(
                List.of(source), output, "Custom Name", null, "dev"));

        assertEquals("Custom Name", result.collectionName());
        assertEquals("dev", result.environment());
        assertEquals(List.of("dev"), PostmanConversion.environmentNames(tmp));
        JsonNode collection = parseJson(Files.readString(output, StandardCharsets.UTF_8));
        assertEquals(List.of("baseUrl=https://dev.example.com"), pairs(collection.get("variable")));
    }

    @Test
    void directorySourceIsScannedRecursively() throws Exception {
        write(tmp.resolve("a.http"), "### A\nGET http://api.example.com/a\n");
        write(tmp.resolve("sub/b.rest"), "### B\nGET http://api.example.com/b\n");
        Path output = tmp.resolve("nested/dir/collection.json");

        ConversionResult result = PostmanConversion.convert(new ConversionRequest(
                List.of(tmp), output, null, null, null));

        assertEquals(2, result.requests());
        assertEquals(2, result.folders());
        JsonNode collection = parseJson(Files.readString(output, StandardCharsets.UTF_8));
        assertEquals(2, collection.get("item").size());
    }

    @Test
    void unknownEnvironmentFails() throws Exception {
        Path source = tmp.resolve("api.http");
        write(source, "### R\nGET http://api.example.com/x\n");
        write(tmp.resolve("http-client.env.json"), "{\"dev\": {\"a\": \"b\"}}");

        ConversionException error = assertThrows(ConversionException.class, () ->
                PostmanConversion.convert(new ConversionRequest(
                        List.of(source), tmp.resolve("c.json"), null, null, "prod")));

        assertTrue(error.getMessage().contains("prod"));
    }

    @Test
    void missingSourceFails() {
        ConversionException error = assertThrows(ConversionException.class, () ->
                PostmanConversion.convert(new ConversionRequest(
                        List.of(tmp.resolve("absent.http")), tmp.resolve("c.json"), null, null, null)));

        assertTrue(error.getMessage().contains("does not exist"));
    }

    @Test
    void nonHttpFileFails() throws Exception {
        Path source = tmp.resolve("data.json");
        write(source, "{}");

        assertThrows(ConversionException.class, () ->
                PostmanConversion.convert(new ConversionRequest(
                        List.of(source), tmp.resolve("c.json"), null, null, null)));
    }
}
