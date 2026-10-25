/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.validation;

import com.auhouhs.http2postman.converter.PostmanConverter;
import com.auhouhs.http2postman.parser.HttpFileParser;
import com.auhouhs.http2postman.postman.PostmanJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.auhouhs.http2postman.TestSupport.fixturePath;
import static com.auhouhs.http2postman.TestSupport.parseJson;
import static com.auhouhs.http2postman.TestSupport.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural validation tests: findings text and reported size. */
class CollectionValidatorTest {

    private static final String INFO = "\"info\": {\"name\": \"x\", \"description\": \"d\", \"schema\": \"s\"}";

    private static JsonNode convertFixture(String name) throws IOException {
        return tree(PostmanConverter.convert(
                List.of(HttpFileParser.parseFile(fixturePath(name))), "t"));
    }

    @Test
    void validCollectionNoFindings() throws IOException {
        CollectionValidator.Result result = CollectionValidator.validate(convertFixture("headers_sniff.http"));
        assertEquals(List.of(), result.findings());
        assertTrue(result.utf8Size() > 0);
    }

    @Test
    void jsonParseability() throws Exception {
        JsonNode collection = convertFixture("methods.http");
        String serialized = PostmanJson.mapper().writeValueAsString(collection);
        JsonNode reparsed = PostmanJson.mapper().readTree(serialized);
        assertEquals(List.of(), CollectionValidator.validate(reparsed).findings());
    }

    @Test
    void everyRequestHasMethodUrlRawNameHeader() throws IOException {
        JsonNode collection = convertFixture("methods.http");
        List<JsonNode> requests = new ArrayList<>();
        walk(collection.get("item"), requests);
        assertEquals(9, requests.size());
        for (JsonNode request : requests) {
            assertTrue(request.get("method").isTextual());
            assertTrue(request.get("url").get("raw").isTextual());
            assertTrue(request.get("header").isArray());
        }
    }

    private static void walk(JsonNode items, List<JsonNode> collector) {
        for (JsonNode item : items) {
            if (item.has("request")) {
                collector.add(item.get("request"));
            } else if (item.has("item")) {
                walk(item.get("item"), collector);
            }
        }
    }

    @Test
    void missingInfoNameFound() {
        CollectionValidator.Result result = CollectionValidator.validate(parseJson("{\"item\": []}"));
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("info")));
    }

    @Test
    void missingItemFound() {
        CollectionValidator.Result result = CollectionValidator.validate(parseJson("{" + INFO + "}"));
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("item")));
    }

    @Test
    void badHeaderFound() {
        String json = "{" + INFO + ", \"item\": [{\"name\": \"r\", \"request\": {"
                + "\"method\": \"GET\", \"url\": {\"raw\": \"http://x.test/\"},"
                + " \"header\": [{\"key\": \"K\", \"value\": 5}]}}]}";
        CollectionValidator.Result result = CollectionValidator.validate(parseJson(json));
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("header")));
    }

    @Test
    void emptyHeaderKeyFound() {
        String json = "{" + INFO + ", \"item\": [{\"name\": \"r\", \"request\": {"
                + "\"method\": \"GET\", \"url\": {\"raw\": \"http://x.test/\"},"
                + " \"header\": [{\"key\": \"\", \"value\": \"v\"}]}}]}";
        CollectionValidator.Result result = CollectionValidator.validate(parseJson(json));
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("non-empty string")));
    }

    @Test
    void emptyVariableFound() {
        String json = "{" + INFO + ", \"item\": [], \"variable\": [{\"key\": \"k\", \"value\": \"\"}]}";
        CollectionValidator.Result result = CollectionValidator.validate(parseJson(json));
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("variable")));
    }

    @Test
    void sizeReported() throws Exception {
        JsonNode collection = parseJson("{" + INFO + ", \"item\": []}");
        CollectionValidator.Result result = CollectionValidator.validate(collection);
        assertEquals(List.of(), result.findings());
        assertEquals(PostmanJson.compactSize(collection), result.utf8Size());
    }

    @Test
    void validEventNoFindings() {
        String json = "{" + INFO + ", \"item\": [{\"name\": \"r\", \"request\": {"
                + "\"method\": \"GET\", \"url\": {\"raw\": \"http://x.test/\"}, \"header\": []},"
                + " \"event\": ["
                + "{\"listen\": \"test\", \"script\": {\"type\": \"text/javascript\","
                + " \"exec\": [\"pm.test('a');\"]}},"
                + "{\"listen\": \"prerequest\", \"script\": {\"exec\": [\"pm.variables.set('k', 'v');\"]}}"
                + "]}]}";
        assertEquals(List.of(), CollectionValidator.validate(parseJson(json)).findings());
    }

    @Test
    void badEventListenFound() {
        String json = "{" + INFO + ", \"item\": [{\"name\": \"r\", \"request\": {"
                + "\"method\": \"GET\", \"url\": {\"raw\": \"http://x.test/\"}, \"header\": []},"
                + " \"event\": [{\"listen\": \"weird\", \"script\": {\"exec\": [\"x;\"]}}]}]}";
        CollectionValidator.Result result = CollectionValidator.validate(parseJson(json));
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("listen")));
    }

    @Test
    void badEventExecFound() {
        String json = "{" + INFO + ", \"item\": [{\"name\": \"r\", \"request\": {"
                + "\"method\": \"GET\", \"url\": {\"raw\": \"http://x.test/\"}, \"header\": []},"
                + " \"event\": [{\"listen\": \"test\", \"script\": {\"exec\": [\"ok\", 42]}}]}]}";
        CollectionValidator.Result result = CollectionValidator.validate(parseJson(json));
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("exec")));
    }

    @Test
    void nonObjectRootYieldsBothFindings() {
        CollectionValidator.Result result = CollectionValidator.validate(parseJson("[]"));
        assertFalse(result.isValid());
        assertTrue(result.findings().contains("missing info object"));
        assertTrue(result.findings().contains("item must be an array"));
    }
}
