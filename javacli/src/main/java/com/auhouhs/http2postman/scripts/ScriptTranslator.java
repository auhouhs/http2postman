/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.scripts;

import com.auhouhs.http2postman.model.Script;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translate JetBrains HTTP client script API calls into Postman's {@code pm.*} API.
 *
 * <p>Pure and stateless. A rename-only translation: no source line is ever
 * dropped, unrecognized statements pass through byte-for-byte.
 */
public final class ScriptTranslator {

    private record Rule(Pattern pattern, String replacement) {
    }

    // Rule order matters: longest/contextual first (e.g. response.body. before
    // bare response.body; client.global.clearAll before client.global.clear).
    //
    // Bare response.body maps to pm.response.json() because JetBrains always
    // exposes a parsed body object. client.assert is a TRUTHINESS check in
    // JetBrains, so it becomes pm.expect(!!(cond)).to.be.true.
    // request.environment.get reads the environment-merged variable chain in
    // JetBrains; Postman's closest equivalent is pm.variables.get, which
    // resolves through local -> environment -> collection -> global.
    private static final List<Rule> RULES = List.of(
            rule("response\\.body\\.", "pm.response.json()."),
            rule("response\\.body(?![\\w.])", "pm.response.json()"),
            rule("response\\.status\\b", "pm.response.code"),
            rule("response\\.contentType\\b", "pm.response.headers.get(\"Content-Type\")"),
            rule("response\\.time\\b", "pm.response.responseTime"),
            rule("response\\.headers\\.valueOf\\(", "pm.response.headers.get("),
            rule("client\\.test\\(", "pm.test("),
            rule("client\\.log\\(", "console.log("),
            rule("client\\.global\\.clearAll\\(", "pm.collectionVariables.clear("),
            rule("client\\.global\\.clear\\(", "pm.collectionVariables.unset("),
            rule("client\\.global\\.set\\(", "pm.collectionVariables.set("),
            rule("client\\.global\\.get\\(", "pm.collectionVariables.get("),
            rule("client\\.variables\\.request\\.set\\(", "pm.variables.set("),
            rule("client\\.variables\\.request\\.get\\(", "pm.variables.get("),
            rule("client\\.variables\\.global\\.set\\(", "pm.collectionVariables.set("),
            rule("client\\.variables\\.global\\.get\\(", "pm.collectionVariables.get("),
            rule("client\\.variables\\.environment\\.set\\(", "pm.environment.set("),
            rule("client\\.variables\\.environment\\.get\\(", "pm.environment.get("),
            rule("request\\.variables\\.set\\(", "pm.variables.set("),
            rule("request\\.variables\\.get\\(", "pm.variables.get("),
            rule("request\\.environment\\.set\\(", "pm.environment.set("),
            rule("request\\.environment\\.get\\(", "pm.variables.get("));

    private static final String ASSERT_CALL = "client.assert(";

    private ScriptTranslator() {
    }

    private static Rule rule(String pattern, String replacement) {
        return new Rule(Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS), replacement);
    }

    /**
     * Translate script source lines. Both script kinds translate the same, so
     * {@code kind} only documents intent.
     *
     * <p>The output always has exactly as many lines as the input.
     */
    public static List<String> translate(Script.Kind kind, List<String> lines) {
        String text = String.join("\n", lines);
        for (Rule rule : RULES) {
            text = rule.pattern().matcher(text).replaceAll(Matcher.quoteReplacement(rule.replacement()));
        }
        text = rewriteAsserts(text);
        return Arrays.asList(text.split("\n", -1));
    }

    /**
     * Rewrite every {@code client.assert(...)} call into
     * {@code pm.expect(!!(cond)[, msg]).to.be.true}, ignoring strings and comments.
     *
     * <p>Postman's sandbox does not support Chai's {@code .to.be.truthy}, so
     * JetBrains' truthiness semantics are preserved with {@code !!(...)} plus the
     * documented {@code .to.be.true} assertion.
     */
    private static String rewriteAsserts(String text) {
        StringBuilder out = new StringBuilder(text.length() + 32);
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`') {
                int end = skipString(text, i);
                out.append(text, i, end);
                i = end;
                continue;
            }
            if (text.startsWith("//", i)) {
                out.append(text.substring(i));
                break;
            }
            if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                if (end == -1) {
                    out.append(text.substring(i));
                    break;
                }
                end += 2;
                out.append(text, i, end);
                i = end;
                continue;
            }
            if (text.startsWith(ASSERT_CALL, i)) {
                int close = matchingClose(text, i + ASSERT_CALL.length() - 1);
                if (close != -1) {
                    String args = text.substring(i + ASSERT_CALL.length(), close);
                    int comma = firstTopLevelComma(args);
                    if (comma != -1) {
                        out.append("pm.expect(!!(").append(args, 0, comma).append(')')
                                .append(args, comma, args.length()).append(").to.be.true");
                    } else {
                        out.append("pm.expect(!!(").append(args).append(")).to.be.true");
                    }
                    i = close + 1;
                    continue;
                }
                out.append(text.substring(i));
                break;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    /** Index of the first top-level comma (outside nested parens, strings, comments), or -1. */
    private static int firstTopLevelComma(String text) {
        int depth = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`') {
                i = skipString(text, i);
                continue;
            }
            if (text.startsWith("//", i)) {
                break;
            }
            if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                if (end == -1) {
                    break;
                }
                i = end + 2;
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** Index just past the string literal starting at {@code start}; length when unterminated. */
    private static int skipString(String text, int start) {
        char quote = text.charAt(start);
        int i = start + 1;
        int n = text.length();
        while (i < n) {
            if (text.charAt(i) == '\\') {
                i += 2;
                continue;
            }
            if (text.charAt(i) == quote) {
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /**
     * Index of the ')' closing the '(' at {@code openIndex}, or -1. Skips string
     * literals and comments; a {@code //} comment before the close means the
     * statement is unterminated on that line.
     */
    private static int matchingClose(String text, int openIndex) {
        int depth = 0;
        int i = openIndex;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`') {
                i = skipString(text, i);
                continue;
            }
            if (text.startsWith("//", i)) {
                return -1;
            }
            if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                if (end == -1) {
                    return -1;
                }
                i = end + 2;
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /** Convenience overload for callers that do not care about the script kind. */
    public static List<String> translate(List<String> lines) {
        return translate(Script.Kind.TEST, lines);
    }
}
