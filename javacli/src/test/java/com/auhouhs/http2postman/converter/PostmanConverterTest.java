/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.converter;

import com.auhouhs.http2postman.model.Body;
import com.auhouhs.http2postman.model.BodyPart;
import com.auhouhs.http2postman.model.Request;
import com.auhouhs.http2postman.model.SourceDoc;
import com.auhouhs.http2postman.model.UnsupportedItem;
import com.auhouhs.http2postman.parser.HttpFileParser;
import com.auhouhs.http2postman.postman.PostmanJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.auhouhs.http2postman.TestSupport.fixturePath;
import static com.auhouhs.http2postman.TestSupport.pairs;
import static com.auhouhs.http2postman.TestSupport.texts;
import static com.auhouhs.http2postman.TestSupport.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Converter tests: URL building, headers, bodies, names, variables, events, auth. */
class PostmanConverterTest {

    private static SourceDoc parse(String... lines) {
        return HttpFileParser.parseLines(List.of(lines));
    }

    private static JsonNode convert(SourceDoc doc) {
        return tree(PostmanConverter.convert(List.of(doc), "t"));
    }

    private static JsonNode convert(SourceDoc doc, List<String> sourcePaths) {
        return tree(PostmanConverter.convert(List.of(doc), "t", sourcePaths, new ArrayList<>(), null));
    }

    private static JsonNode item(JsonNode collection, int index) {
        return collection.get("item").get(index);
    }

    private static JsonNode request(JsonNode collection, int index) {
        return item(collection, index).get("request");
    }

    private static JsonNode url(JsonNode collection, int index) {
        return request(collection, index).get("url");
    }

    private static JsonNode body(JsonNode collection, int index) {
        return request(collection, index).get("body");
    }

    private static Map<String, String> keyValues(JsonNode array) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode node : array) {
            values.put(node.get("key").asText(), node.get("value").asText());
        }
        return values;
    }

    // ------------------------------------------------------------------
    // URL builder
    // ------------------------------------------------------------------

    @Test
    void fullSplit() {
        JsonNode url = url(convert(parse("GET http://api.example.com:8443/users/1?page=2&flag")), 0);
        assertEquals("http://api.example.com:8443/users/1?page=2&flag", url.get("raw").asText());
        assertEquals("http", url.get("protocol").asText());
        assertEquals(List.of("api", "example", "com"), texts(url.get("host")));
        assertEquals("8443", url.get("port").asText());
        assertEquals(List.of("users", "1"), texts(url.get("path")));
        assertEquals(List.of("page=2", "flag="), pairs(url.get("query")));
    }

    @Test
    void tokenHostByteForByte() {
        JsonNode url = url(convert(parse("GET http://{{host}}/users?flag&page=2")), 0);
        assertEquals(List.of("{{host}}"), texts(url.get("host")));
        assertEquals(List.of("users"), texts(url.get("path")));
        assertEquals(List.of("flag=", "page=2"), pairs(url.get("query")));
        assertTrue(url.get("raw").asText().contains("{{host}}"));
    }

    @Test
    void tokenScheme() {
        JsonNode url = url(convert(parse("GET {{scheme}}://api.example.com/items")), 0);
        assertEquals("{{scheme}}", url.get("protocol").asText());
        assertEquals(List.of("api", "example", "com"), texts(url.get("host")));
    }

    @Test
    void tokenObscuredFallbackRawPlusBestEffort() {
        JsonNode url = url(convert(parse("GET {{base}}/users/{{id}}")), 0);
        assertEquals("{{base}}/users/{{id}}", url.get("raw").asText());
        assertEquals(List.of("{{base}}"), texts(url.get("host")));
        assertEquals(List.of("users", "{{id}}"), texts(url.get("path")));
    }

    @Test
    void fragmentStaysInRawOnly() {
        JsonNode url = url(convert(parse("GET https://api.example.com:8443/path#fragment")), 0);
        assertEquals("https://api.example.com:8443/path#fragment", url.get("raw").asText());
        if (url.has("path")) {
            assertFalse(url.get("path").toString().contains("fragment"));
        }
        assertEquals("8443", url.get("port").asText());
    }

    @Test
    void relativeUrlOmitsProtocolHost() {
        JsonNode url = url(convert(parse("GET /relative/path?x=1")), 0);
        assertFalse(url.has("protocol"));
        assertFalse(url.has("host"));
        assertEquals(List.of("relative", "path"), texts(url.get("path")));
        assertEquals(List.of("x=1"), pairs(url.get("query")));
    }

    @Test
    void trailingAndDoubleSlashSegmentsPreserved() {
        JsonNode collection = convert(parse(
                "### a", "GET http://x.test/api/", "", "### b", "GET http://x.test/a//b"));
        assertEquals(List.of("api", ""), texts(url(collection, 0).get("path")));
        assertEquals(List.of("a", "", "b"), texts(url(collection, 1).get("path")));
    }

    @Test
    void dottedTokenHostNotSplit() {
        JsonNode url = url(convert(parse("### a", "GET http://{{host.sub}}/")), 0);
        assertEquals(List.of("{{host.sub}}"), texts(url.get("host")));
    }

    @Test
    void queryPlusAndEncodedBracesNotDecoded() {
        JsonNode url = url(convert(parse("GET http://x.test/a?name=b+c&q=%7B%7Btoken%7D%7D")), 0);
        assertEquals(List.of("name=b+c", "q=%7B%7Btoken%7D%7D"), pairs(url.get("query")));
    }

    // ------------------------------------------------------------------
    // headers
    // ------------------------------------------------------------------

    @Test
    void headerOrderCasingDropContentLengthKeepAuth() {
        JsonNode collection = convert(parse(
                "POST http://x.test/a",
                "X-First: one",
                "x-lower: two",
                "Content-Length: 123",
                "Authorization: Bearer abc",
                "",
                "{\"a\": 1}"));
        List<String> pairs = new ArrayList<>();
        for (JsonNode header : request(collection, 0).get("header")) {
            pairs.add(header.get("key").asText() + "=" + header.get("value").asText());
        }
        assertEquals(List.of("X-First=one", "x-lower=two", "Authorization=Bearer abc",
                "Content-Type=application/json"), pairs);
    }

    @Test
    void emptyHeaderArray() {
        JsonNode collection = convert(parse("GET http://x.test/a"));
        assertEquals("[]", request(collection, 0).get("header").toString());
    }

    // ------------------------------------------------------------------
    // bodies
    // ------------------------------------------------------------------

    @Test
    void rawLanguageJson() {
        JsonNode body = body(convert(parse(
                "POST http://x.test/a", "Content-Type: application/json", "", "{\"a\": 1}")), 0);
        assertEquals("raw", body.get("mode").asText());
        assertEquals("json", body.get("options").get("raw").get("language").asText());
        assertEquals("{\"a\": 1}", body.get("raw").asText());
    }

    @Test
    void formdataFileVsText() {
        Body body = Body.multipart(List.of(BodyPart.text("note", "hi"), BodyPart.file("f", "a.txt")));
        Request request = new Request("r", "POST", "http://x.test/", List.of(), body, "f.http", 1);
        SourceDoc doc = new SourceDoc(List.of(request));
        JsonNode formdata = body(convert(doc), 0).get("formdata");
        assertEquals("[{\"key\":\"note\",\"type\":\"text\",\"value\":\"hi\"},"
                + "{\"key\":\"f\",\"type\":\"file\",\"src\":\"a.txt\"}]", formdata.toString());
    }

    @Test
    void fileBodyMode() {
        Body body = Body.file("./blob.bin");
        Request request = new Request("r", "POST", "http://x.test/", List.of(), body, "f.http", 1);
        JsonNode out = body(convert(new SourceDoc(List.of(request))), 0);
        assertEquals("file", out.get("mode").asText());
        assertEquals("./blob.bin", out.get("file").get("src").asText());
    }

    @Test
    void urlencodedBody() {
        JsonNode body = body(convert(parse(
                "POST http://x.test/a",
                "Content-Type: application/x-www-form-urlencoded",
                "",
                "a=1&b=two")), 0);
        assertEquals("urlencoded", body.get("mode").asText());
        assertEquals(List.of("a=1", "b=two"), pairs(body.get("urlencoded")));
    }

    // ------------------------------------------------------------------
    // names
    // ------------------------------------------------------------------

    @Test
    void collisionSuffixes() {
        JsonNode collection = convert(parse(
                "### Same", "GET http://x.test/1", "", "### Same", "GET http://x.test/2", "",
                "### Same", "GET http://x.test/3"));
        assertEquals(List.of("Same", "Same (2)", "Same (3)"), texts(names(collection)));
    }

    private static JsonNode names(JsonNode collection) {
        var array = PostmanJson.mapper().createArrayNode();
        for (JsonNode item : collection.get("item")) {
            array.add(item.get("name").asText());
        }
        return array;
    }

    @Test
    void fallbackMethodUrl() {
        JsonNode collection = convert(parse("POST http://x.test/a"));
        assertEquals("POST http://x.test/a", item(collection, 0).get("name").asText());
    }

    @Test
    void collisionAcrossFoldersIsCollectionWide() {
        JsonNode collection = convert(parse(
                "## A", "### Same", "GET http://x.test/1", "", "## B", "### Same", "GET http://x.test/2"));
        assertEquals("Same", item(collection, 0).get("item").get(0).get("name").asText());
        assertEquals("Same (2)", item(collection, 1).get("item").get(0).get("name").asText());
    }

    @Test
    void collisionAcrossSourceFilesIsCollectionWide() {
        SourceDoc doc1 = parse("### Same", "GET http://x.test/1");
        SourceDoc doc2 = parse("### Same", "GET http://x.test/2");
        JsonNode collection = tree(PostmanConverter.convert(List.of(doc1, doc2), "t",
                List.of("/tmp/one.http", "/tmp/two.http"), new ArrayList<>(), null));
        assertEquals("Same", collection.get("item").get(0).get("item").get(0).get("name").asText());
        assertEquals("Same (2)", collection.get("item").get(1).get("item").get(0).get("name").asText());
    }

    @Test
    void generatedSuffixesAreReserved() {
        JsonNode collection = convert(parse(
                "### Same", "GET http://x.test/1", "", "### Same", "GET http://x.test/2", "",
                "### Same (2)", "GET http://x.test/3"));
        List<String> names = new ArrayList<>();
        for (JsonNode item : collection.get("item")) {
            names.add(item.get("name").asText());
        }
        assertEquals(3, names.stream().distinct().count());
    }

    // ------------------------------------------------------------------
    // folders
    // ------------------------------------------------------------------

    @Test
    void parserFoldersBecomeItems() throws IOException {
        JsonNode collection = convert(HttpFileParser.parseFile(fixturePath("folders.http")));
        assertEquals(List.of("Users", "Admin"), texts(names(collection)));
        List<String> children = new ArrayList<>();
        for (JsonNode item : item(collection, 0).get("item")) {
            children.add(item.get("name").asText());
        }
        assertEquals(List.of("List users", "Create user"), children);
    }

    @Test
    void multiFileWrapsInFolders() {
        SourceDoc doc1 = parse("### A", "GET http://x.test/a");
        SourceDoc doc2 = parse("### B", "GET http://x.test/b");
        JsonNode collection = tree(PostmanConverter.convert(List.of(doc1, doc2), "t",
                List.of("/tmp/one.http", "/tmp/two.http"), new ArrayList<>(), null));
        assertEquals(List.of("One", "Two"), texts(names(collection)));
        assertEquals("A", item(collection, 0).get("item").get(0).get("name").asText());
        assertEquals("B", item(collection, 1).get("item").get(0).get("name").asText());
    }

    // ------------------------------------------------------------------
    // variables
    // ------------------------------------------------------------------

    @Test
    void collectionVariables() {
        JsonNode collection = convert(parse("@base = https://x.test", "@empty =", "### r",
                "GET {{base}}/a"));
        assertEquals(List.of("base=https://x.test"), pairs(collection.get("variable")));
    }

    @Test
    void dynamicVariableMapping() {
        JsonNode collection = convert(parse(
                "### r",
                "POST http://x.test/a",
                "",
                "{\"id\": \"$uuid\", \"t\": \"$timestamp\", \"n\": \"$randomInt\", "
                        + "\"g\": \"$random.uuid\", \"m\": \"$random.integer(5,10)\"}"));
        String raw = body(collection, 0).get("raw").asText();
        assertTrue(raw.contains("{{$guid}}"));
        assertTrue(raw.contains("{{$timestamp}}"));
        assertTrue(raw.contains("{{$randomInt}}"));
        assertTrue(raw.contains("{{$randomUUID}}"));
        assertFalse(raw.contains("$uuid"));
        assertFalse(raw.contains("$random.integer"));
    }

    @Test
    void urlTokensKeptAndQueryUnchanged() {
        JsonNode url = url(convert(parse("GET http://{{host}}/u?k={{token}}&q=1")), 0);
        assertEquals(List.of("k={{token}}", "q=1"), pairs(url.get("query")));
    }

    @Test
    void unmappableAndDotenvReported() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("dynamic_vars.http"));
        boolean dotenvWarning = false;
        for (UnsupportedItem warning : doc.warnings()) {
            if (warning.kind() == UnsupportedItem.Kind.DOTENV) {
                dotenvWarning = true;
            }
        }
        assertTrue(dotenvWarning);

        List<UnsupportedItem> warnings = new ArrayList<>();
        PostmanConverter.convert(List.of(doc), "t", null, warnings, null);
        assertEquals(List.of("dynamic_variable"),
                warnings.stream().map(warning -> warning.kind().wireName()).toList());
    }

    @Test
    void mappingDoesNotTouchBodyOutsideUrl() {
        JsonNode collection = convert(parse("### r", "GET http://x.test/a", "",
                "$uuid and $timestamp"));
        assertEquals("{{$guid}} and {{$timestamp}}", body(collection, 0).get("raw").asText());
    }

    @Test
    void extendedRandomMapping() {
        SourceDoc doc = parse(
                "### r",
                "GET http://x.test/a?e={{$random.email}}&w=$random.word&al=$random.alphanumeric"
                        + "&ip=$random.ipv6&u=$random.username&c=$random.company&ph=$random.phone");
        List<UnsupportedItem> warnings = new ArrayList<>();
        JsonNode collection = tree(PostmanConverter.convert(List.of(doc), "t", null, warnings, null));
        String raw = url(collection, 0).get("raw").asText();
        assertTrue(raw.contains("{{$randomEmail}}"));
        assertTrue(raw.contains("{{$randomWord}}"));
        assertTrue(raw.contains("{{$randomAlphaNumeric}}"));
        assertTrue(raw.contains("{{$randomIPV6}}"));
        assertTrue(raw.contains("{{$randomUserName}}"));
        assertTrue(raw.contains("{{$randomCompanyName}}"));
        assertTrue(raw.contains("{{$randomPhoneNumber}}"));
        assertFalse(raw.contains("$random.email"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void multisegmentAndUnknownRandomReported() {
        SourceDoc doc = parse("### r",
                "GET http://x.test/a?x=$random.address.fullAddress&y=$random.xyz&z={{$random.nope}}");
        List<UnsupportedItem> warnings = new ArrayList<>();
        JsonNode collection = tree(PostmanConverter.convert(List.of(doc), "t", null, warnings, null));
        String raw = url(collection, 0).get("raw").asText();
        assertTrue(raw.contains("$random.address.fullAddress"));
        assertTrue(raw.contains("$random.xyz"));
        assertTrue(raw.contains("{{$random.nope}}"));
        assertEquals(3, warnings.size());
        for (UnsupportedItem warning : warnings) {
            assertEquals(UnsupportedItem.Kind.DYNAMIC_VARIABLE, warning.kind());
        }
    }

    @Test
    void isoTimestamp() {
        JsonNode collection = convert(parse("### r", "GET http://x.test/a?t=$isoTimestamp"));
        assertTrue(url(collection, 0).get("raw").asText().contains("{{$isoTimestamp}}"));
    }

    @Test
    void bracedTableTokensNotDoubleBraced() {
        JsonNode collection = convert(parse("### r",
                "GET http://x.test/a?u={{$uuid}}&t={{$timestamp}}&ru={{$random.uuid}}"
                        + "&ri={{$random.integer(1,9)}}&n={{$randomInt}}&iso={{$isoTimestamp}}"));
        String raw = url(collection, 0).get("raw").asText();
        assertTrue(raw.contains("u={{$guid}}"));
        assertTrue(raw.contains("t={{$timestamp}}"));
        assertTrue(raw.contains("ru={{$randomUUID}}"));
        assertTrue(raw.contains("ri={{$randomInt}}"));
        assertTrue(raw.contains("n={{$randomInt}}"));
        assertTrue(raw.contains("iso={{$isoTimestamp}}"));
        assertFalse(raw.contains("{{{{"));
    }

    @Test
    void unmappableRandomAndHeaderTokensReported() {
        SourceDoc doc = parse("### r", "GET http://x.test/a?x=$random.word", "X-Corr: $uuid",
                "X-Other: $custom");
        List<UnsupportedItem> warnings = new ArrayList<>();
        JsonNode collection = tree(PostmanConverter.convert(List.of(doc), "t", null, warnings, null));
        assertEquals(1, warnings.size());
        JsonNode header = request(collection, 0).get("header");
        assertEquals("{\"key\":\"X-Corr\",\"value\":\"{{$guid}}\"}", header.get(0).toString());
        assertEquals("{\"key\":\"X-Other\",\"value\":\"$custom\"}", header.get(1).toString());
    }

    // ------------------------------------------------------------------
    // info
    // ------------------------------------------------------------------

    @Test
    void infoFields() {
        SourceDoc doc = parse("### r", "GET http://x.test/a");
        JsonNode collection = convert(doc, List.of("/tmp/my.http"));
        assertEquals("t", collection.get("info").get("name").asText());
        assertEquals(PostmanJson.SCHEMA_URL, collection.get("info").get("schema").asText());
        assertTrue(collection.get("info").get("description").asText().contains("/tmp/my.http"));
    }

    @Test
    void infoCustomName() {
        SourceDoc doc = parse("### r", "GET http://x.test/a");
        JsonNode collection = tree(PostmanConverter.convert(List.of(doc), "My Collection",
                List.of("/tmp/my.http"), new ArrayList<>(), null));
        assertEquals("My Collection", collection.get("info").get("name").asText());
        assertEquals(PostmanJson.SCHEMA_URL, collection.get("info").get("schema").asText());
        assertTrue(collection.get("info").get("description").asText().contains("/tmp/my.http"));
    }

    // ------------------------------------------------------------------
    // script events
    // ------------------------------------------------------------------

    @Test
    void eventsEmittedForScriptedRequests() {
        JsonNode collection = convert(parse(
                "### r",
                "< {%",
                "    request.variables.set(\"x\", \"y\");",
                "%}",
                "POST http://x.test/a",
                "",
                "body",
                "> {% client.test('ok', function() {}); %}"));
        JsonNode item = item(collection, 0);
        Map<String, JsonNode> events = new LinkedHashMap<>();
        for (JsonNode event : item.get("event")) {
            events.put(event.get("listen").asText(), event);
        }
        assertNotNull(events.get("prerequest"));
        assertNotNull(events.get("test"));
        for (JsonNode event : events.values()) {
            assertEquals("text/javascript", event.get("script").get("type").asText());
            assertTrue(event.get("script").get("exec").isArray());
            for (JsonNode line : event.get("script").get("exec")) {
                assertTrue(line.isTextual());
            }
        }
        assertEquals(List.of("    pm.variables.set(\"x\", \"y\");"),
                texts(events.get("prerequest").get("script").get("exec")));
        assertTrue(texts(events.get("test").get("script").get("exec")).get(0).startsWith("pm.test("));
    }

    @Test
    void noEventForScriptFreeRequests() {
        JsonNode collection = convert(parse("### r", "GET http://x.test/a"));
        assertFalse(item(collection, 0).has("event"));
    }

    @Test
    void multipleBlocksConcatenatedIntoOneEvent() {
        JsonNode collection = convert(parse(
                "### r",
                "POST http://x.test/a",
                "",
                "body",
                "> {% client.test('a'); %}",
                "# comment between blocks",
                "> {% client.log('b'); %}"));
        JsonNode events = item(collection, 0).get("event");
        assertEquals(1, events.size());
        assertEquals("test", events.get(0).get("listen").asText());
        List<String> exec = texts(events.get(0).get("script").get("exec"));
        assertEquals("pm.test('a');", exec.get(0));
        assertEquals("console.log('b');", exec.get(1));
        assertFalse(String.join("\n", exec).contains("comment"));
    }

    @Test
    void responseScriptFixtureEvents() throws IOException {
        JsonNode collection = convert(HttpFileParser.parseFile(fixturePath("response_script.http")));
        List<JsonNode> testEvents = new ArrayList<>();
        for (JsonNode event : item(collection, 0).get("event")) {
            if ("test".equals(event.get("listen").asText())) {
                testEvents.add(event);
            }
        }
        assertEquals(1, testEvents.size());
        List<String> exec = texts(testEvents.get(0).get("script").get("exec"));
        assertTrue(exec.stream().anyMatch(line -> line.startsWith("pm.test(")));
        assertTrue(exec.stream().anyMatch(line -> line.contains("client.") || line.contains("console.log")));
    }

    @Test
    void envDefaultsOverrideFileVariables() {
        SourceDoc doc = parse("@baseUrl = https://file.example.com", "@onlyFile = f", "### r",
                "GET {{baseUrl}}/x");
        JsonNode collection = tree(PostmanConverter.convert(List.of(doc), "t", null,
                new ArrayList<>(), Map.of("baseUrl", "https://env.example.com")));
        Map<String, String> variables = keyValues(collection.get("variable"));
        assertEquals("https://env.example.com", variables.get("baseUrl"));
        assertEquals("f", variables.get("onlyFile"));
    }

    @Test
    void envDefaultsAlone() {
        SourceDoc doc = parse("### r", "GET http://x.test/a");
        JsonNode collection = tree(PostmanConverter.convert(List.of(doc), "t", null,
                new ArrayList<>(), Map.of("fresh", "value")));
        assertEquals(List.of("fresh=value"), pairs(collection.get("variable")));
    }

    // ------------------------------------------------------------------
    // auth fixture
    // ------------------------------------------------------------------

    @Test
    void authFixture() throws IOException {
        JsonNode collection = convert(HttpFileParser.parseFile(fixturePath("auth_client_credentials.http")));
        Map<String, JsonNode> items = new LinkedHashMap<>();
        for (JsonNode item : collection.get("item")) {
            items.put(item.get("name").asText(), item);
        }

        JsonNode token = items.get("OAuth2 token request");
        assertEquals("oauth2", token.get("auth").get("type").asText());
        Map<String, String> oauthValues = keyValues(token.get("auth").get("oauth2"));
        assertEquals("client-1", oauthValues.get("clientId"));
        assertEquals("secret-1", oauthValues.get("clientSecret"));
        assertEquals("https://identity.example.com/oauth2/token", oauthValues.get("accessTokenUrl"));
        assertFalse(headerKeys(token).contains("Authorization"));

        JsonNode bearer = items.get("Bearer downstream");
        assertEquals("bearer", bearer.get("auth").get("type").asText());
        assertFalse(headerKeys(bearer).contains("Authorization"));

        JsonNode literal = items.get("Literal basic");
        assertEquals("basic", literal.get("auth").get("type").asText());
        Map<String, String> basicValues = keyValues(literal.get("auth").get("basic"));
        assertEquals("alice", basicValues.get("username"));
        assertEquals("pass", basicValues.get("password"));

        JsonNode twoVar = items.get("Basic two variables");
        assertEquals("basic", twoVar.get("auth").get("type").asText());
        Map<String, String> twoVarValues = keyValues(twoVar.get("auth").get("basic"));
        assertEquals("{{userName}}", twoVarValues.get("username"));
        assertEquals("{{passWord}}", twoVarValues.get("password"));

        JsonNode untouched = items.get("Untouched bearer header (literal token)");
        assertNotEquals(null, untouched);
        assertFalse(untouched.has("auth"));
        assertEquals("[{\"key\":\"Authorization\",\"value\":\"Bearer abc123\"}]",
                untouched.get("request").get("header").toString());
    }

    private static List<String> headerKeys(JsonNode item) {
        List<String> keys = new ArrayList<>();
        for (JsonNode header : item.get("request").get("header")) {
            keys.add(header.get("key").asText());
        }
        return keys;
    }
}
