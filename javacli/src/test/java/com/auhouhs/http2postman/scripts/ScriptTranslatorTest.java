/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.scripts;

import com.auhouhs.http2postman.model.Script;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Script translation tests: rule table + "no line dropped" invariant. */
class ScriptTranslatorTest {

    private static final Script.Kind TEST = Script.Kind.TEST;
    private static final Script.Kind PRE = Script.Kind.PREREQUEST;

    private static void assertTranslated(Script.Kind kind, List<String> source, String... expected) {
        assertEquals(List.of(expected), ScriptTranslator.translate(kind, source));
    }

    @Test
    void responseBodyField() {
        assertTranslated(TEST, List.of("response.body.access_token"), "pm.response.json().access_token");
        assertTranslated(TEST, List.of("response.body.message.length"),
                "pm.response.json().message.length");
    }

    @Test
    void bareResponseBody() {
        assertTranslated(TEST, List.of("const b = response.body;"), "const b = pm.response.json();");
    }

    @Test
    void responseStatus() {
        assertTranslated(TEST, List.of("response.status === 200"), "pm.response.code === 200");
    }

    @Test
    void responseContentType() {
        assertTranslated(TEST, List.of("response.contentType"),
                "pm.response.headers.get(\"Content-Type\")");
    }

    @Test
    void responseTime() {
        assertTranslated(TEST, List.of("response.time"), "pm.response.responseTime");
    }

    @Test
    void responseHeadersValueOf() {
        assertTranslated(TEST, List.of("response.headers.valueOf(\"X-Trace\")"),
                "pm.response.headers.get(\"X-Trace\")");
    }

    @Test
    void clientTest() {
        assertTranslated(TEST, List.of("client.test(\"ok\", function () {"),
                "pm.test(\"ok\", function () {");
    }

    @Test
    void clientLog() {
        assertTranslated(TEST, List.of("client.log(\"done\");"), "console.log(\"done\");");
    }

    @Test
    void clientAssertOneArg() {
        assertTranslated(TEST, List.of("client.assert(response.status === 200);"),
                "pm.expect(!!(pm.response.code === 200)).to.be.true;");
    }

    @Test
    void clientAssertTwoArgs() {
        assertTranslated(TEST, List.of("client.assert(response.status === 200, \"Expected 200\");"),
                "pm.expect(!!(pm.response.code === 200), \"Expected 200\").to.be.true;");
    }

    @Test
    void clientAssertNestedParens() {
        assertTranslated(TEST, List.of("client.assert(x, \"nested ( ) parens\");"),
                "pm.expect(!!(x), \"nested ( ) parens\").to.be.true;");
    }

    @Test
    void clientAssertTruthySemantics() {
        // JetBrains client.assert is a truthiness check: a non-empty string must PASS.
        List<String> out = ScriptTranslator.translate(TEST, List.of(
                "client.assert(response.body && response.body.access_token, \"Token response contains access_token\");"));
        assertEquals(List.of(
                "pm.expect(!!(pm.response.json() && pm.response.json().access_token), "
                        + "\"Token response contains access_token\").to.be.true;"),
                out);
    }

    @Test
    void bareResponseBodyIsParsedJsonNotText() {
        List<String> out = ScriptTranslator.translate(TEST,
                List.of("const t = response.body && response.body.token;"));
        assertEquals(List.of("const t = pm.response.json() && pm.response.json().token;"), out);
    }

    @Test
    void clientAssertAssignmentContext() {
        assertTranslated(TEST, List.of("const ok = client.assert(a === b);"),
                "const ok = pm.expect(!!(a === b)).to.be.true;");
    }

    @Test
    void clientAssertMultilineStatement() {
        assertTranslated(TEST,
                List.of("client.assert(",
                        "    response.status === 200,",
                        "    \"Expected HTTP 200\"",
                        ");",
                        "client.log('after');"),
                "pm.expect(!!(",
                "    pm.response.code === 200),",
                "    \"Expected HTTP 200\"",
                ").to.be.true;",
                "console.log('after');");
    }

    @Test
    void clientAssertMultilineTrailingCodeOnCloseLine() {
        assertTranslated(TEST, List.of("client.assert(a === b", "); foo();"),
                "pm.expect(!!(a === b", ")).to.be.true; foo();");
    }

    @Test
    void clientAssertStringLiteralCloseParen() {
        assertTranslated(TEST, List.of("client.assert(x == \")\");"),
                "pm.expect(!!(x == \")\")).to.be.true;");
    }

    @Test
    void clientAssertMultilineStringLiteralParen() {
        assertTranslated(TEST,
                List.of("client.assert(",
                        "  JSON.stringify(y).includes(\")\"),",
                        "  \"msg\"",
                        ");"),
                "pm.expect(!!(",
                "  JSON.stringify(y).includes(\")\")),",
                "  \"msg\"",
                ").to.be.true;");
    }

    @Test
    void twoAssertsOnOneLine() {
        assertTranslated(TEST, List.of("if (a) client.assert(b); client.assert(c);"),
                "if (a) pm.expect(!!(b)).to.be.true; pm.expect(!!(c)).to.be.true;");
    }

    @Test
    void assertWithLineCommentBeforeCloseLeftUntouched() {
        assertTranslated(TEST, List.of("client.assert(a", "  // note: b", ");"),
                "client.assert(a", "  // note: b", ");");
    }

    @Test
    void clientGlobalSetGet() {
        assertTranslated(PRE, List.of("client.global.set(\"k\", v);", "client.global.get(\"k\");"),
                "pm.collectionVariables.set(\"k\", v);", "pm.collectionVariables.get(\"k\");");
    }

    @Test
    void clientGlobalClear() {
        assertTranslated(PRE, List.of("client.global.clear(\"k\");"),
                "pm.collectionVariables.unset(\"k\");");
    }

    @Test
    void clientGlobalClearAll() {
        assertTranslated(PRE, List.of("client.global.clearAll();"),
                "pm.collectionVariables.clear();");
    }

    @Test
    void scopedClientVariables() {
        List<List<String>> cases = List.of(
                List.of("client.variables.request.set(\"k\", v);", "pm.variables.set(\"k\", v);"),
                List.of("client.variables.request.get(\"k\");", "pm.variables.get(\"k\");"),
                List.of("client.variables.global.set(\"k\", v);", "pm.collectionVariables.set(\"k\", v);"),
                List.of("client.variables.environment.get(\"k\");", "pm.environment.get(\"k\");"));
        for (List<String> pair : cases) {
            assertEquals(List.of(pair.get(1)), ScriptTranslator.translate(PRE, List.of(pair.get(0))));
        }
    }

    @Test
    void clientVariablesFilePassesThrough() {
        assertTranslated(PRE, List.of("client.variables.file.get(\"k\");"),
                "client.variables.file.get(\"k\");");
    }

    @Test
    void requestVariables() {
        assertTranslated(PRE, List.of("request.variables.set(\"k\", v);", "request.variables.get(\"k\");"),
                "pm.variables.set(\"k\", v);", "pm.variables.get(\"k\");");
    }

    @Test
    void requestEnvironment() {
        assertTranslated(PRE,
                List.of("request.environment.get(\"brokerClientId\");", "request.environment.set(\"k\", v);"),
                "pm.variables.get(\"brokerClientId\");", "pm.environment.set(\"k\", v);");
    }

    @Test
    void unrecognizedStatementsUnchanged() {
        List<String> source = List.of(
                "const expirationDate = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();",
                "foo.bar();",
                "if (response.code === 200) {",
                "}");
        assertEquals(source, ScriptTranslator.translate(PRE, source));
    }

    @Test
    void blankLinesPreserved() {
        assertTranslated(PRE, List.of("", "  ", "code"), "", "  ", "code");
    }

    @Test
    void noLineDropped() {
        List<String> source = List.of(
                "> {%",
                "client.test(\"ok\", function () {",
                "    client.assert(response.status === 200);",
                "",
                "    const b = response.body;",
                "});",
                "%}");
        List<String> out = ScriptTranslator.translate(TEST, source);
        assertEquals(source.size(), out.size());
        assertEquals("%}", out.get(6));
    }
}
