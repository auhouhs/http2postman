/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman;

import com.auhouhs.http2postman.model.DocItem;
import com.auhouhs.http2postman.model.Request;
import com.auhouhs.http2postman.model.SourceDoc;
import com.auhouhs.http2postman.postman.PostmanJson;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Shared helpers for the ported test-suite. */
public final class TestSupport {

    /** Fixtures copied from the upstream Python test-suite. */
    public static final Path FIXTURES = Path.of("src/test/resources/fixtures").toAbsolutePath();

    private TestSupport() {
    }

    public static String fixture(String name) {
        return FIXTURES.resolve(name).toString();
    }

    public static Path fixturePath(String name) {
        return FIXTURES.resolve(name);
    }

    /** Convert a Postman document object into its JSON tree. */
    public static JsonNode tree(Object value) {
        return PostmanJson.toTree(value);
    }

    public static JsonNode parseJson(String json) {
        try {
            return PostmanJson.mapper().readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("invalid test JSON", e);
        }
    }

    /** Text values of a JSON array, keeping order. */
    public static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            out.add(node.asText());
        }
        return out;
    }

    public static List<Request> requests(SourceDoc doc) {
        List<Request> out = new ArrayList<>();
        for (DocItem item : doc.items()) {
            if (item instanceof Request request) {
                out.add(request);
            }
        }
        return out;
    }

    public static Request request(SourceDoc doc, int index) {
        return requests(doc).get(index);
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** {@code {"key": ..., "value": ...}} pairs rendered as {@code "key=value"} for assertions. */
    public static List<String> pairs(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            out.add(node.get("key").asText() + "=" + node.get("value").asText());
        }
        return out;
    }
}
