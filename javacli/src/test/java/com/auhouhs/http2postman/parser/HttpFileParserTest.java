/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.parser;

import com.auhouhs.http2postman.model.Body;
import com.auhouhs.http2postman.model.BodyPart;
import com.auhouhs.http2postman.model.Folder;
import com.auhouhs.http2postman.model.FormField;
import com.auhouhs.http2postman.model.Header;
import com.auhouhs.http2postman.model.HttpVersion;
import com.auhouhs.http2postman.model.Request;
import com.auhouhs.http2postman.model.Script;
import com.auhouhs.http2postman.model.SourceDoc;
import com.auhouhs.http2postman.model.UnsupportedItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.auhouhs.http2postman.TestSupport.fixture;
import static com.auhouhs.http2postman.TestSupport.fixturePath;
import static com.auhouhs.http2postman.TestSupport.request;
import static com.auhouhs.http2postman.TestSupport.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser tests: names, methods, headers, bodies, scripts, dotenv, WS/gRPC. */
class HttpFileParserTest {

    @TempDir
    Path tmp;

    private static SourceDoc parseLines(String... lines) {
        return HttpFileParser.parseLines(List.of(lines));
    }

    private static SourceDoc parseLines(Map<String, String> env, String... lines) {
        return HttpFileParser.parseLines(List.of(lines), env);
    }

    private static Map<String, String> headers(Request request) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Header header : request.headers()) {
            map.put(header.key(), header.value());
        }
        return map;
    }

    private static Request requestByUrl(SourceDoc doc, String url) {
        for (Request request : requests(doc)) {
            if (url.equals(request.url())) {
                return request;
            }
        }
        throw new AssertionError("no request with url " + url);
    }

    // ------------------------------------------------------------------
    // names / methods
    // ------------------------------------------------------------------

    @Test
    void hashNamesAndAtNameOverride() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("names.http"));
        List<String> names = new ArrayList<>();
        for (Request request : requests(doc)) {
            names.add(request.name());
        }
        assertEquals(List.of("Get all users", "createUser", "overrideMe"), names);
    }

    @Test
    void sourceFileAndLineRecorded() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("names.http"));
        Request first = request(doc, 0);
        assertEquals(fixture("names.http"), first.sourceFile());
        assertEquals(1, first.sourceLine());
    }

    @Test
    void allMethodsAndBareUrl() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("methods.http"));
        List<String> expected = List.of(
                "GET http://api.example.com/bare",
                "GET http://api.example.com/get",
                "POST http://api.example.com/post",
                "PUT http://api.example.com/put",
                "PATCH http://api.example.com/patch",
                "DELETE http://api.example.com/delete",
                "HEAD http://api.example.com/head",
                "OPTIONS http://api.example.com/options",
                "TRACE http://api.example.com/trace");
        List<String> got = new ArrayList<>();
        for (Request request : requests(doc)) {
            got.add(request.method() + " " + request.url());
        }
        assertEquals(expected, got);
    }

    // ------------------------------------------------------------------
    // folded request line (long URLs split over indented continuation lines)
    // ------------------------------------------------------------------

    @Test
    void foldedUrlSegmentsJoinedWithoutSeparator() {
        SourceDoc doc = parseLines(
                "GET http://example.com:8080",
                "    /api",
                "    /html",
                "    /get",
                "    ?id=123",
                "    &value=content");
        Request r = request(doc, 0);
        assertEquals("GET", r.method());
        assertEquals("http://example.com:8080/api/html/get?id=123&value=content", r.url());
        assertEquals(1, r.sourceLine());
    }

    @Test
    void foldedUrlLeavesHeadersBodyAndScriptIndentUntouched() {
        SourceDoc doc = parseLines(
                "### scripted",
                "< {%",
                "    client.log(\"pre\");",
                "%}",
                "POST http://x.test/items?",
                "    page=2&",
                "    size=10",
                "Content-Type: application/json",
                "",
                "{",
                "  \"a\": 1",
                "}");
        Request r = request(doc, 0);
        assertEquals("http://x.test/items?page=2&size=10", r.url());
        assertEquals("application/json", headers(r).get("Content-Type"));
        assertEquals("{\n  \"a\": 1\n}", r.body().content());
        assertEquals(List.of("    client.log(\"pre\");"), r.preScripts().get(0).lines());
    }

    // ------------------------------------------------------------------
    // HTTP version pinned after the URL
    // ------------------------------------------------------------------

    @Test
    void httpVersionPinStrippedFromUrl() {
        SourceDoc doc = parseLines(
                "### http2",
                "GET https://example.org HTTP/2",
                "",
                "### http11",
                "PATCH https://example.org HTTP/1.1",
                "",
                "### prior knowledge",
                "GET https://example.org HTTP/2 (Prior Knowledge)");
        assertEquals("https://example.org", request(doc, 0).url());
        assertEquals(HttpVersion.HTTP_2, request(doc, 0).httpVersion());
        assertEquals("PATCH", request(doc, 1).method());
        assertEquals("https://example.org", request(doc, 1).url());
        assertEquals(HttpVersion.HTTP_1_1, request(doc, 1).httpVersion());
        assertEquals("https://example.org", request(doc, 2).url());
        assertEquals(HttpVersion.HTTP_2_PRIOR_KNOWLEDGE, request(doc, 2).httpVersion());
    }

    @Test
    void httpVersionAfterFoldedUrl() {
        SourceDoc doc = parseLines(
                "GET https://example.org/api",
                "    ?id=1 HTTP/2");
        assertEquals("https://example.org/api?id=1", request(doc, 0).url());
        assertEquals(HttpVersion.HTTP_2, request(doc, 0).httpVersion());
    }

    @Test
    void urlWithoutVersionLeavesVersionUnset() {
        SourceDoc doc = parseLines("GET https://example.org");
        assertNull(request(doc, 0).httpVersion());
    }

    // ------------------------------------------------------------------
    // response file redirects
    // ------------------------------------------------------------------

    @Test
    void responseFileDirectiveReportedAndNotTreatedAsScript() {
        SourceDoc doc = parseLines(
                "### Force own filename",
                "GET https://example.org/get",
                "",
                "//TIP overrides an existing file",
                ">>! {{$historyFolder}}/my-forced-response.json");
        Request r = request(doc, 0);
        assertEquals("https://example.org/get", r.url());
        assertNull(r.body());
        assertEquals(0, r.postScripts().size());
        UnsupportedItem warning = doc.warnings().get(0);
        assertEquals(UnsupportedItem.Kind.RESPONSE_FILE, warning.kind());
        assertEquals(5, warning.sourceLine());
    }

    @Test
    void inlineResponseFileTargetStrippedFromUrl() {
        SourceDoc doc = parseLines("GET https://example.org/get >>! ./out.json");
        assertEquals("https://example.org/get", request(doc, 0).url());
        assertEquals(UnsupportedItem.Kind.RESPONSE_FILE, doc.warnings().get(0).kind());
    }

    @Test
    void singleArrowStillStartsPostScript() {
        SourceDoc doc = parseLines(
                "GET https://example.org/get",
                "",
                "> {% client.log('ok'); %}");
        assertEquals(1, request(doc, 0).postScripts().size());
        assertTrue(doc.warnings().isEmpty());
    }

    @Test
    void responseFileAfterHandlerScriptStillReported() {
        SourceDoc doc = parseLines(
                "GET https://example.org/get",
                "",
                "> {% client.log('ok'); %}",
                ">> force.json");
        assertEquals(1, request(doc, 0).postScripts().size());
        UnsupportedItem warning = doc.warnings().get(0);
        assertEquals(UnsupportedItem.Kind.RESPONSE_FILE, warning.kind());
        assertEquals(4, warning.sourceLine());
    }

    // ------------------------------------------------------------------
    // headers / content-type sniffing
    // ------------------------------------------------------------------

    @Test
    void explicitContentTypeKept() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("headers_sniff.http"));
        assertEquals("application/json", headers(request(doc, 0)).get("Content-Type"));
    }

    @Test
    void jsonSniff() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("headers_sniff.http"));
        Request r = request(doc, 1);
        assertEquals("application/json", headers(r).get("Content-Type"));
        assertEquals(Body.Kind.RAW, r.body().kind());
        assertEquals("{\"a\": 1}", r.body().content());
    }

    @Test
    void xmlSniff() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("headers_sniff.http"));
        assertEquals("application/xml", headers(request(doc, 2)).get("Content-Type"));
    }

    @Test
    void formSniff() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("headers_sniff.http"));
        Request r = request(doc, 3);
        assertEquals("application/x-www-form-urlencoded", headers(r).get("Content-Type"));
        assertEquals(Body.Kind.FORM_URLENCODED, r.body().kind());
        assertEquals(List.of(new FormField("a", "1"), new FormField("b", "2")), r.body().form());
    }

    @Test
    void textSniff() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("headers_sniff.http"));
        assertEquals("text/plain", headers(request(doc, 4)).get("Content-Type"));
    }

    @Test
    void headerStopAtBlankLine() {
        SourceDoc doc = parseLines(
                "GET http://x.test/a",
                "Accept: application/json",
                "X-One: 1",
                "",
                "body");
        Request r = request(doc, 0);
        List<String> pairs = new ArrayList<>();
        for (Header header : r.headers()) {
            pairs.add(header.key() + "=" + header.value());
        }
        assertEquals(List.of("Accept=application/json", "X-One=1", "Content-Type=text/plain"), pairs);
        assertEquals("body", r.body().content());
    }

    // ------------------------------------------------------------------
    // variables
    // ------------------------------------------------------------------

    @Test
    void fileLevelVariables() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("variables.http"));
        assertEquals(Map.of("base", "https://api.example.com", "token", "abc123"), doc.variables());
    }

    @Test
    void variablesWithAndWithoutSpaces() {
        SourceDoc doc = parseLines("@a = 1", "@b=2", "### r", "GET http://x.test/");
        assertEquals(Map.of("a", "1", "b", "2"), doc.variables());
    }

    @Test
    void atLineInBodyIsVerbatim() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("variables.http"));
        Request bodyRequest = requestByUrl(doc, "{{base}}/echo");
        assertEquals("line one\n@x = y\nline three", bodyRequest.body().content());
        assertNull(doc.variables().get("x"));
    }

    @Test
    void variableBetweenRequests() {
        SourceDoc doc = parseLines(
                "### first",
                "GET http://x.test/a",
                "",
                "### second",
                "GET http://x.test/b",
                "",
                "@mid = yes",
                "### third",
                "GET http://x.test/c");
        assertEquals(Map.of("mid", "yes"), doc.variables());
        assertEquals(3, doc.items().size());
    }

    @Test
    void variablesAfterTitleHashBeforeRequestLine() {
        SourceDoc doc = parseLines(
                "### Scenario title",
                "@contentType = application/json",
                "@baseUrl = https://x.test",
                "",
                "### Real request",
                "POST {{baseUrl}}/a",
                "",
                "{\"a\": 1}");
        assertEquals(Map.of("contentType", "application/json", "baseUrl", "https://x.test"), doc.variables());
        assertEquals(1, doc.items().size());
        assertEquals("POST", request(doc, 0).method());
        assertEquals("{{baseUrl}}/a", request(doc, 0).url());
    }

    // ------------------------------------------------------------------
    // bodies
    // ------------------------------------------------------------------

    @Test
    void rawBodyVerbatimMultiline() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("raw_body.http"));
        Request r = request(doc, 0);
        assertEquals(Body.Kind.RAW, r.body().kind());
        assertEquals("first line\nsecond line\nthird line", r.body().content());
    }

    @Test
    void urlencodedPairs() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("urlencoded.http"));
        Request r = request(doc, 0);
        assertEquals(Body.Kind.FORM_URLENCODED, r.body().kind());
        assertEquals(List.of(new FormField("username", "alice"), new FormField("password", "s3cret"),
                new FormField("flag", "")), r.body().form());
    }

    @Test
    void externalRefInlined() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("external_ref.http"));
        Request r = request(doc, 0);
        assertEquals(Body.Kind.RAW, r.body().kind());
        assertEquals("{\"id\": 42, \"name\": \"fixture\"}\n", r.body().content());
    }

    @Test
    void missingRefPlaceholderAndReport() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("external_ref.http"));
        Request r = request(doc, 1);
        assertEquals(Body.Kind.RAW, r.body().kind());
        assertTrue(r.body().content().contains("unreadable external reference"));
        boolean hasWarning = false;
        UnsupportedItem entry = null;
        for (UnsupportedItem warning : doc.warnings()) {
            if (warning.kind() == UnsupportedItem.Kind.EXTERNAL_REF_UNREADABLE) {
                hasWarning = true;
                entry = warning;
            }
        }
        assertTrue(hasWarning);
        assertEquals(fixture("external_ref.http"), entry.sourceFile());
        assertTrue(entry.sourceLine() > 0);
    }

    @Test
    void multipartPartsMultilineAndFile() throws Exception {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("multipart.http"), tmp, null);
        Request r = request(doc, 0);
        assertEquals(Body.Kind.MULTIPART, r.body().kind());
        List<BodyPart> parts = r.body().parts();
        assertEquals(BodyPart.text("title", "Multi-line\npart value"), parts.get(0));
        assertEquals(BodyPart.file("attachment", "empty.txt"), parts.get(1));
        assertEquals(BodyPart.text("note", "short"), parts.get(2));
        Path filePath = tmp.resolve("empty.txt");
        assertTrue(Files.exists(filePath));
        assertEquals(0, Files.size(filePath));
    }

    @Test
    void existingFileNotOverwritten() throws Exception {
        Path existing = tmp.resolve("empty.txt");
        Files.writeString(existing, "precious", StandardCharsets.UTF_8);
        HttpFileParser.parseFile(fixturePath("multipart.http"), tmp, null);
        assertEquals("precious", Files.readString(existing, StandardCharsets.UTF_8));
    }

    @Test
    void traversalFilenameCreatesNothingOutsideOutputDir() throws Exception {
        Path path = tmp.resolve("x.http");
        Files.writeString(path,
                "POST http://x.test/a\n"
                        + "Content-Type: multipart/form-data; boundary=zz\n\n"
                        + "--zz\n"
                        + "Content-Disposition: form-data; name=\"f\"; filename=\"../../escape.txt\"\n\n"
                        + "--zz--\n",
                StandardCharsets.UTF_8);
        SourceDoc doc = HttpFileParser.parseFile(path, tmp, null);
        assertEquals("../../escape.txt", request(doc, 0).body().parts().get(0).filename());
        Path escaped = tmp.getParent().resolve("escape.txt");
        assertFalse(Files.exists(escaped));
        assertFalse(Files.exists(tmp.resolve("..").resolve("escape.txt")));
    }

    @Test
    void multipartWithoutBoundaryIsTextAndReported() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("multipart_no_boundary.http"));
        assertEquals(Body.Kind.RAW, request(doc, 0).body().kind());
        assertTrue(hasWarning(doc, UnsupportedItem.Kind.MULTIPART_NO_BOUNDARY));
    }

    @Test
    void unterminatedMultipartReported() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("multipart_unterminated.http"));
        assertEquals(Body.Kind.MULTIPART, request(doc, 0).body().kind());
        assertTrue(hasWarning(doc, UnsupportedItem.Kind.MULTIPART_UNTERMINATED));
    }

    @Test
    void graphqlRawJsonWrapperKept() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("graphql.http"));
        Request r = request(doc, 0);
        assertEquals(Body.Kind.RAW, r.body().kind());
        assertEquals("{\"query\": \"{ users { id name } }\", \"variables\": {\"limit\": 10}}",
                r.body().content());
    }

    @Test
    void graphqlWithoutHeaderUsesNormalRules() {
        SourceDoc doc = parseLines(
                "POST http://x.test/g",
                "Content-Type: application/json",
                "",
                "{\"query\": \"x\"}");
        Request r = request(doc, 0);
        assertEquals(Body.Kind.RAW, r.body().kind());
        assertEquals("{\"query\": \"x\"}", r.body().content());
    }

    // ------------------------------------------------------------------
    // scripts
    // ------------------------------------------------------------------

    @Test
    void handlerCapturedAndNotInBody() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("response_script.http"));
        Request r = request(doc, 0);
        assertEquals("{\"hello\": \"world\"}", r.body().content());
        assertFalse(r.body().content().contains("client.test"));
        assertEquals(2, r.postScripts().size());
        assertEquals(Script.Kind.TEST, r.postScripts().get(0).kind());
        assertTrue(r.postScripts().get(0).lines().get(0).startsWith("client.test("));
        assertTrue(r.postScripts().get(1).lines().get(0).startsWith("client.log("));
    }

    @Test
    void handlerTerminatesBodyCapture() {
        SourceDoc doc = parseLines(
                "POST http://x.test/a",
                "",
                "body-line",
                "> {% client.log('x'); %}",
                "GET http://x.test/b");
        assertEquals("body-line", request(doc, 0).body().content());
        assertEquals(2, doc.items().size());
        assertEquals("http://x.test/b", request(doc, 1).url());
    }

    @Test
    void multilineHandlerContinuationLinesCaptured() {
        SourceDoc doc = parseLines(
                "POST http://x.test/a",
                "",
                "body-line",
                "> {%",
                "    client.test(\"ok\", function () {",
                "        client.assert(response.status === 200);",
                "    });",
                "    client.global.set(\"token\", response.body.access_token);",
                "%}",
                "### next",
                "GET http://x.test/b");
        assertEquals(2, doc.items().size());
        assertEquals("http://x.test/a", request(doc, 0).url());
        assertEquals("http://x.test/b", request(doc, 1).url());
        List<String> lines = request(doc, 0).postScripts().get(0).lines();
        assertEquals("    client.test(\"ok\", function () {", lines.get(0));
        assertTrue(lines.contains("    });"));
        assertFalse(request(doc, 0).body().content().contains("client."));
    }

    @Test
    void multilinePreRequestScriptCaptured() {
        SourceDoc doc = parseLines(
                "### with pre-request script",
                "< {%",
                "    request.variables.set(\"x\", \"y\");",
                "    client.global.set(\"token\", \"abc\");",
                "%}",
                "POST http://x.test/a",
                "Content-Type: application/json",
                "",
                "{\"a\": 1}");
        assertEquals(1, doc.items().size());
        Request r = request(doc, 0);
        assertEquals("POST", r.method());
        assertEquals("http://x.test/a", r.url());
        assertEquals("{\"a\": 1}", r.body().content());
        assertFalse(r.body().content().contains("client."));
        assertEquals(1, r.preScripts().size());
        assertEquals(Script.Kind.PREREQUEST, r.preScripts().get(0).kind());
        assertEquals("    request.variables.set(\"x\", \"y\");", r.preScripts().get(0).lines().get(0));
        assertEquals("    client.global.set(\"token\", \"abc\");", r.preScripts().get(0).lines().get(1));
        assertEquals(List.of(), doc.warnings());
    }

    @Test
    void preRequestScriptInBodyPosition() {
        SourceDoc doc = parseLines(
                "POST http://x.test/a",
                "Content-Type: text/plain",
                "",
                "< {%",
                "    console.log(\"hi\");",
                "%}",
                "real body line");
        Request r = request(doc, 0);
        assertEquals("real body line", r.body().content());
        assertEquals(Script.Kind.PREREQUEST, r.preScripts().get(0).kind());
        assertEquals(List.of("    console.log(\"hi\");"), r.preScripts().get(0).lines());
        assertEquals(List.of(), doc.warnings());
    }

    @Test
    void folderAfterResponseScript() {
        SourceDoc doc = parseLines(
                "### one",
                "POST http://x.test/a",
                "",
                "body",
                "> {% client.log('x'); %}",
                "",
                "## Admin",
                "### two",
                "GET http://x.test/b");
        assertEquals(2, doc.items().size());
        assertEquals("one", ((Request) doc.items().get(0)).name());
        Folder folder = assertInstanceOf(Folder.class, doc.items().get(1));
        assertEquals("Admin", folder.name());
        assertEquals(List.of("two"), folder.items().stream()
                .map(item -> ((Request) item).name()).toList());
    }

    @Test
    void firstBodyLinesStartingWithCommentOrAtAreVerbatim() {
        SourceDoc doc = parseLines(
                "POST http://x.test/a",
                "",
                "#important note",
                "second line");
        assertEquals("#important note\nsecond line", request(doc, 0).body().content());

        doc = parseLines(
                "POST http://x.test/b",
                "",
                "@x = y",
                "third line");
        assertEquals("@x = y\nthird line", request(doc, 0).body().content());
        assertEquals(Map.of(), doc.variables());
    }

    @Test
    void bodyLinesLookingLikeXmlOrNotesAreNotRefs() {
        SourceDoc doc = parseLines(
                "POST http://x.test/a",
                "",
                "<- this is a note",
                "more");
        assertEquals("<- this is a note\nmore", request(doc, 0).body().content());
        assertEquals(List.of(), doc.warnings());

        doc = parseLines(
                "POST http://x.test/b",
                "",
                "<root",
                "line2",
                "</root>");
        assertEquals("<root\nline2\n</root>", request(doc, 0).body().content());
    }

    @Test
    void refWithCharsetSuffix() throws Exception {
        Path payload = tmp.resolve("p.json");
        Files.writeString(payload, "{\"ok\": true}", StandardCharsets.UTF_8);
        Path path = tmp.resolve("x.http");
        Files.writeString(path,
                "POST http://x.test/a\n"
                        + "Content-Type: application/json\n\n"
                        + "< ./p.json; charset=UTF-8\n",
                StandardCharsets.UTF_8);
        SourceDoc doc = HttpFileParser.parseFile(path);
        assertEquals("{\"ok\": true}", request(doc, 0).body().content());
        assertEquals(List.of(), doc.warnings());
    }

    // ------------------------------------------------------------------
    // folders
    // ------------------------------------------------------------------

    @Test
    void hashFolderAndGroup() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("folders.http"));
        assertEquals(2, doc.items().size());
        Folder users = assertInstanceOf(Folder.class, doc.items().get(0));
        assertEquals("Users", users.name());
        Folder admin = assertInstanceOf(Folder.class, doc.items().get(1));
        assertEquals("Admin", admin.name());
        List<String> inUsers = new ArrayList<>();
        for (var item : users.items()) {
            inUsers.add(((Request) item).name());
        }
        assertEquals(List.of("List users", "Create user"), inUsers);
    }

    @Test
    void groupWithEqualsIsVariable() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("folders.http"));
        assertEquals("settings_value", doc.variables().get("group"));
        Folder last = assertInstanceOf(Folder.class, doc.items().get(doc.items().size() - 1));
        List<String> names = new ArrayList<>();
        for (var item : last.items()) {
            names.add(((Request) item).name());
        }
        assertEquals(List.of("Admin panel", "Settings"), names);
    }

    // ------------------------------------------------------------------
    // failure modes
    // ------------------------------------------------------------------

    @Test
    void nonUtf8Raises() throws Exception {
        Path path = tmp.resolve("bad.http");
        Files.write(path, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0x81});
        assertThrows(InvalidEncodingException.class, () -> HttpFileParser.parseFile(path));
    }

    @Test
    void zeroRequestFile() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("empty.http"));
        assertEquals(List.of(), doc.items());
        assertEquals(Map.of("lonely", "value"), doc.variables());
    }

    // ------------------------------------------------------------------
    // script capture details
    // ------------------------------------------------------------------

    @Test
    void singlelinePreScriptHeaderPositionDoesNotSwallowNextRequest() {
        SourceDoc doc = parseLines(
                "### r",
                "< {% client.global.set(\"a\", \"b\"); %}",
                "GET http://x.test/a");
        assertEquals(1, doc.items().size());
        Request r = request(doc, 0);
        assertEquals("http://x.test/a", r.url());
        assertEquals(Script.Kind.PREREQUEST, r.preScripts().get(0).kind());
        assertEquals(List.of("client.global.set(\"a\", \"b\");"), r.preScripts().get(0).lines());
    }

    @Test
    void bodyPositionPreScriptAfterCommentIsCapturedNotLeaked() {
        SourceDoc doc = parseLines(
                "POST http://x.test/a",
                "",
                "# comment",
                "< {% console.log('x'); %}",
                "real body");
        Request r = request(doc, 0);
        assertFalse(r.body().content().contains("console.log"));
        assertEquals(Script.Kind.PREREQUEST, r.preScripts().get(0).kind());
        assertEquals(List.of("console.log('x');"), r.preScripts().get(0).lines());
    }

    @Test
    void multilinePreScriptCaptured() {
        SourceDoc doc = parseLines(
                "### r",
                "< {%",
                "    request.variables.set(\"x\", \"y\");",
                "",
                "    client.global.set(\"t\", \"abc\");",
                "%}",
                "POST http://x.test/a",
                "",
                "{\"a\": 1}");
        Request r = request(doc, 0);
        assertEquals(1, r.preScripts().size());
        List<String> lines = r.preScripts().get(0).lines();
        assertEquals("    request.variables.set(\"x\", \"y\");", lines.get(0));
        assertEquals("", lines.get(1));
        assertEquals("    client.global.set(\"t\", \"abc\");", lines.get(2));
    }

    @Test
    void multilineResponseScriptCaptured() {
        SourceDoc doc = parseLines(
                "POST http://x.test/a",
                "",
                "body-line",
                "> {%",
                "    client.test(\"ok\", function () {",
                "        client.assert(response.status === 200);",
                "    });",
                "%}",
                "### next",
                "GET http://x.test/b");
        assertEquals(2, doc.items().size());
        Request r = request(doc, 0);
        assertEquals(Script.Kind.TEST, r.postScripts().get(0).kind());
        assertEquals("    client.test(\"ok\", function () {", r.postScripts().get(0).lines().get(0));
        assertEquals("    });", r.postScripts().get(0).lines()
                .get(r.postScripts().get(0).lines().size() - 1));
        assertEquals("body-line", r.body().content());
    }

    @Test
    void singlelineResponseScriptCaptured() {
        SourceDoc doc = parseLines(
                "POST http://x.test/a",
                "",
                "body",
                "> {% client.test('a'); %}");
        assertEquals(List.of("client.test('a');"), request(doc, 0).postScripts().get(0).lines());
    }

    @Test
    void commentBetweenBlocksNotMerged() {
        SourceDoc doc = parseLines(
                "POST http://x.test/a",
                "",
                "body",
                "> {% client.test('a'); %}",
                "# comment",
                "> {% client.log('b'); %}");
        Request r = request(doc, 0);
        assertEquals(2, r.postScripts().size());
        assertEquals(List.of("client.test('a');"), r.postScripts().get(0).lines());
        assertEquals(List.of("client.log('b');"), r.postScripts().get(1).lines());
    }

    @Test
    void noScriptWarningsEmitted() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("response_script.http"));
        for (UnsupportedItem warning : doc.warnings()) {
            assertNotEquals(UnsupportedItem.Kind.EXTERNAL_REF_UNREADABLE, warning.kind());
        }
        assertFalse(hasWarning(doc, UnsupportedItem.Kind.WEBSOCKET_REQUEST));
        assertFalse(hasWarning(doc, UnsupportedItem.Kind.GRPC_REQUEST));
    }

    // ------------------------------------------------------------------
    // dotenv
    // ------------------------------------------------------------------

    @Test
    void dotenvResolutionInUrlHeaderBodyAndForm() {
        SourceDoc doc = parseLines(
                Map.of("BASE", "https://api.example.com", "TOKEN", "t-123", "NAME", "world"),
                "### r",
                "POST {{$dotenv BASE}}/x",
                "X-Token: {{$dotenv TOKEN}}",
                "",
                "line one {{$dotenv NAME}}");
        Request r = request(doc, 0);
        assertEquals("https://api.example.com/x", r.url());
        assertEquals("t-123", r.headers().get(0).value());
        assertEquals("line one world", r.body().content());
        assertEquals(List.of(), doc.warnings());
    }

    @Test
    void verbatimOutsideToken() {
        SourceDoc doc = parseLines(
                Map.of("A", "V"),
                "### r",
                "POST http://x.test/a",
                "",
                "  keep  {{$dotenv A}}  exact");
        assertEquals("  keep  V  exact", request(doc, 0).body().content());
    }

    @Test
    void missingKeyKeptAndReported() {
        SourceDoc doc = parseLines(
                Map.of("OTHER", "v"),
                "### r",
                "GET http://x.test/{{$dotenv MISSING}}");
        assertEquals("http://x.test/{{$dotenv MISSING}}", request(doc, 0).url());
        assertEquals(1, doc.warnings().size());
        assertEquals(UnsupportedItem.Kind.DOTENV, doc.warnings().get(0).kind());
        assertTrue(doc.warnings().get(0).sourceLine() > 0);
    }

    @Test
    void noEnvFileKeptAndReported() {
        SourceDoc doc = parseLines("### r", "GET http://x.test/{{$dotenv ANY}}");
        assertEquals("http://x.test/{{$dotenv ANY}}", request(doc, 0).url());
        assertEquals(UnsupportedItem.Kind.DOTENV, doc.warnings().get(0).kind());
    }

    @Test
    void discoveryNextToInputNotOutputDir() throws Exception {
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve(".env"), "BASE = https://evil.example.com\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve(".env"),
                "# comment\nBASE = https://good.example.com\nQUOTED = \"hello world\"\n",
                StandardCharsets.UTF_8);
        Path source = tmp.resolve("x.http");
        Files.writeString(source, "### r\nGET {{$dotenv BASE}}/users?q={{$dotenv QUOTED}}\n",
                StandardCharsets.UTF_8);
        SourceDoc doc = HttpFileParser.parseFile(source, outDir, null);
        assertEquals("https://good.example.com/users?q=hello world", request(doc, 0).url());
        assertEquals(List.of(), doc.warnings());
    }

    @Test
    void envPathOverride() throws Exception {
        Files.writeString(tmp.resolve("custom.env"), "BASE = https://override.example.com\n",
                StandardCharsets.UTF_8);
        Path source = tmp.resolve("x.http");
        Files.writeString(source, "### r\nGET {{$dotenv BASE}}/x\n", StandardCharsets.UTF_8);
        SourceDoc doc = HttpFileParser.parseFile(source, null, tmp.resolve("custom.env"));
        assertEquals("https://override.example.com/x", request(doc, 0).url());
    }

    // ------------------------------------------------------------------
    // websocket / grpc
    // ------------------------------------------------------------------

    @Test
    void websocketBlockReportedNotParsed() {
        SourceDoc doc = parseLines(
                "WEBSOCKET wss://echo.example.com/socket",
                "X-Foo: bar",
                "");
        assertEquals(List.of(), doc.items());
        assertEquals(1, doc.warnings().size());
        UnsupportedItem warning = doc.warnings().get(0);
        assertEquals(UnsupportedItem.Kind.WEBSOCKET_REQUEST, warning.kind());
        assertEquals("wss://echo.example.com/socket", warning.message());
        assertEquals(1, warning.sourceLine());
    }

    @Test
    void grpcBlockReportedNotParsed() {
        SourceDoc doc = parseLines(
                "### grpc call",
                "GRPC localhost:8080/hello.Greeter/SayHello",
                "",
                "{\"name\": \"world\"}");
        assertEquals(List.of(), doc.items());
        assertEquals(UnsupportedItem.Kind.GRPC_REQUEST, doc.warnings().get(0).kind());
        assertEquals("localhost:8080/hello.Greeter/SayHello", doc.warnings().get(0).message());
    }

    @Test
    void grpcAfterResponseScript() {
        SourceDoc doc = parseLines(
                "POST http://x.test/a",
                "",
                "body",
                "> {% client.log('x'); %}",
                "GRPC localhost:8080/svc/Method",
                "",
                "{\"a\": 1}");
        assertEquals(1, doc.items().size());
        assertTrue(hasWarning(doc, UnsupportedItem.Kind.GRPC_REQUEST));
    }

    @Test
    void wsBlockHeadersNotLeakedIntoNextRequest() {
        SourceDoc doc = parseLines(
                "WEBSOCKET wss://echo.example.com/socket",
                "X-Foo: bar",
                "",
                "### next",
                "GET http://x.test/b");
        assertEquals(1, doc.items().size());
        assertEquals("http://x.test/b", request(doc, 0).url());
    }

    @Test
    void wsBlockWithHandlerDoesNotCrash() {
        SourceDoc doc = parseLines(
                "WEBSOCKET wss://x.test/socket",
                "",
                "payload",
                "> {% client.test('a'); %}");
        assertEquals(List.of(), doc.items());
        assertEquals(UnsupportedItem.Kind.WEBSOCKET_REQUEST, doc.warnings().get(0).kind());
    }

    @Test
    void unterminatedResponseScriptClosedByHashPreserved() {
        SourceDoc doc = parseLines(
                "POST http://x.test/a",
                "",
                "body",
                "> {%",
                "    client.log('IMPORTANT');",
                "### next",
                "GET http://x.test/b",
                "",
                "body2",
                "> {% client.log('b'); %}");
        assertEquals(2, doc.items().size());
        assertEquals(List.of(List.of("    client.log('IMPORTANT');")),
                request(doc, 0).postScripts().stream().map(Script::lines).toList());
        assertEquals(List.of(List.of("client.log('b');")),
                request(doc, 1).postScripts().stream().map(Script::lines).toList());
    }

    @Test
    void strayHandlerWithoutRequestIsDropped() {
        SourceDoc doc = parseLines(
                "> {% client.test('x'); %}",
                "### r",
                "GET http://x.test/a");
        assertEquals(1, doc.items().size());
        assertEquals("http://x.test/a", request(doc, 0).url());
        assertEquals(List.of(), request(doc, 0).postScripts());
    }

    // ------------------------------------------------------------------
    // fixture driven
    // ------------------------------------------------------------------

    @Test
    void scriptsComplexFixture() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("scripts_complex.http"));
        Request r = request(doc, 0);
        assertEquals(1, r.preScripts().size());
        assertEquals(Script.Kind.PREREQUEST, r.preScripts().get(0).kind());
        assertTrue(r.preScripts().get(0).lines()
                .contains("    request.variables.set(\"traceId\", response.body.traceId);"));
        assertEquals(2, r.postScripts().size());
        assertEquals(4, r.postScripts().get(0).lines().size());
        assertEquals(List.of("client.log(\"second block\");"), r.postScripts().get(1).lines());
    }

    @Test
    void dotenvFixture() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("dotenv.http"));
        Request r = request(doc, 0);
        assertEquals("https://env.example.com/env", r.url());
        assertEquals("quoted-value", r.headers().get(0).value());
        assertEquals("message: hello world", r.body().content());
        assertEquals(List.of(), doc.warnings());
    }

    @Test
    void wsGrpcFixture() throws IOException {
        SourceDoc doc = HttpFileParser.parseFile(fixturePath("ws_grpc.http"));
        assertEquals(1, doc.items().size());
        assertEquals("http://api.example.com/after-ws", request(doc, 0).url());
        assertTrue(hasWarning(doc, UnsupportedItem.Kind.WEBSOCKET_REQUEST));
        assertTrue(hasWarning(doc, UnsupportedItem.Kind.GRPC_REQUEST));
    }

    private static boolean hasWarning(SourceDoc doc, UnsupportedItem.Kind kind) {
        for (UnsupportedItem warning : doc.warnings()) {
            if (warning.kind() == kind) {
                return true;
            }
        }
        return false;
    }
}
