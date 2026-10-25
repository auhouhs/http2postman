/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.env;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code http-client.env.json} reading, merging and value coercion. */
class HttpClientEnvTest {

    @TempDir
    Path tmp;

    private void writeEnv(String filename, String content) throws IOException {
        Files.writeString(tmp.resolve(filename), content, StandardCharsets.UTF_8);
    }

    @Test
    void publicAndPrivateMergedPrivateWins() throws Exception {
        writeEnv("http-client.env.json", """
                {"dev": {"baseUrl": "https://dev.example.com", "apiKey": "dev-key", "port": 8080,
                         "flag": true, "meta": {"a": 1}},
                 "prod": {"baseUrl": "https://prod.example.com"}}
                """);
        writeEnv("http-client.private.env.json", """
                {"dev": {"secret": "s3cret", "apiKey": "private-dev-key"}}
                """);
        Map<String, String> env = HttpClientEnv.load(tmp, "dev");
        assertEquals("https://dev.example.com", env.get("baseUrl"));
        assertEquals("private-dev-key", env.get("apiKey"));
        assertEquals("s3cret", env.get("secret"));
        assertEquals("8080", env.get("port"));
        assertEquals("True", env.get("flag"));
        assertEquals("{\"a\":1}", env.get("meta"));
    }

    @Test
    void prodEnvironment() throws Exception {
        writeEnv("http-client.env.json", """
                {"dev": {"baseUrl": "dev"}, "prod": {"baseUrl": "prod"}}
                """);
        assertEquals(Map.of("baseUrl", "prod"), HttpClientEnv.load(tmp, "prod"));
    }

    @Test
    void missingEnvNameRaises() throws Exception {
        writeEnv("http-client.env.json", "{\"dev\": {\"a\": \"1\"}}");
        EnvFileException error = assertThrows(EnvFileException.class,
                () -> HttpClientEnv.load(tmp, "staging"));
        assertTrue(error.getMessage().contains("environment 'staging' not found"));
    }

    @Test
    void missingFileRaises() {
        assertThrows(EnvFileException.class, () -> HttpClientEnv.load(tmp, "dev"));
    }

    @Test
    void invalidJsonRaises() throws Exception {
        writeEnv("http-client.env.json", "{ not json");
        assertThrows(EnvFileException.class, () -> HttpClientEnv.load(tmp, "dev"));
    }

    @Test
    void nonObjectRootRaises() throws Exception {
        writeEnv("http-client.env.json", "[1, 2]");
        assertThrows(EnvFileException.class, () -> HttpClientEnv.load(tmp, "dev"));
    }
}
