/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.converter;

import com.auhouhs.http2postman.model.Body;
import com.auhouhs.http2postman.model.FormField;
import com.auhouhs.http2postman.model.Header;
import com.auhouhs.http2postman.model.Request;
import com.auhouhs.http2postman.postman.PostmanAuth;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detect OAuth2 / Bearer / Basic authorization patterns and emit Postman
 * {@code auth} objects. Pure functions, no state, no side effects.
 */
public final class AuthDetector {

    private static final Pattern BEARER_TOKEN = Pattern.compile("^Bearer\\s+\\{\\{\\s*([^}\\s]+)\\s*\\}\\}\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BASIC_TWO_VARS = Pattern.compile(
            "^Basic\\s+\\{\\{\\s*([^}\\s]+)\\s*\\}\\}\\s+\\{\\{\\s*([^}\\s]+)\\s*\\}\\}\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BASIC_LITERAL = Pattern.compile("^Basic\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);

    private AuthDetector() {
    }

    /** Return a Postman {@code auth} object for the request, or {@code null}. */
    public static PostmanAuth authForRequest(Request request) {
        return authForRequest(request, null);
    }

    /**
     * Return a Postman {@code auth} object for the request, or {@code null}.
     *
     * @param url overrides the request URL (e.g. the dynamic-variable-mapped one)
     */
    public static PostmanAuth authForRequest(Request request, String url) {
        String effectiveUrl = url != null ? url : request.url();
        String header = authorization(request);
        if (header == null) {
            return null;
        }
        if (isOauth2TokenRequest(request, effectiveUrl)) {
            Matcher literal = BASIC_LITERAL.matcher(header);
            String decoded = literal.matches() ? decodeBase64Utf8(literal.group(1)) : null;
            int colon = decoded == null ? -1 : decoded.indexOf(':');
            if (colon >= 0) {
                return PostmanAuth.oauth2ClientCredentials(effectiveUrl, decoded.substring(0, colon),
                        decoded.substring(colon + 1));
            }
            return null;
        }
        String lowered = header.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("bearer ")) {
            Matcher m = BEARER_TOKEN.matcher(header);
            if (m.matches()) {
                return PostmanAuth.bearer("{{" + m.group(1) + "}}");
            }
            return null;
        }
        if (lowered.startsWith("basic ")) {
            Matcher twoVars = BASIC_TWO_VARS.matcher(header);
            if (twoVars.matches()) {
                return PostmanAuth.basic("{{" + twoVars.group(1) + "}}", "{{" + twoVars.group(2) + "}}");
            }
            Matcher literal = BASIC_LITERAL.matcher(header);
            if (literal.matches() && !literal.group(1).contains("{{")) {
                String decoded = decodeBase64Utf8(literal.group(1));
                int colon = decoded == null ? -1 : decoded.indexOf(':');
                if (colon >= 0) {
                    return PostmanAuth.basic(decoded.substring(0, colon), decoded.substring(colon + 1));
                }
            }
        }
        return null;
    }

    private static String authorization(Request request) {
        for (Header header : request.headers()) {
            if (header.key().equalsIgnoreCase("authorization")) {
                return header.value();
            }
        }
        return null;
    }

    /** All conditions of the OAuth2 client-credentials detection must hold. */
    private static boolean isOauth2TokenRequest(Request request, String url) {
        if (!"POST".equals(request.method())) {
            return false;
        }
        String lowered = url.toLowerCase(Locale.ROOT);
        if (!lowered.contains("token") && !lowered.contains("oauth")) {
            return false;
        }
        Body body = request.body();
        if (body == null || body.kind() != Body.Kind.FORM_URLENCODED) {
            return false;
        }
        boolean clientCredentials = false;
        for (FormField field : body.form()) {
            if ("grant_type".equals(field.name()) && "client_credentials".equals(field.value())) {
                clientCredentials = true;
                break;
            }
        }
        if (!clientCredentials) {
            return false;
        }
        String header = authorization(request);
        if (header == null || !header.toLowerCase(Locale.ROOT).startsWith("basic ")) {
            return false;
        }
        Matcher literal = BASIC_LITERAL.matcher(header);
        if (!literal.matches() || literal.group(1).contains("{{")) {
            return false;
        }
        String decoded = decodeBase64Utf8(literal.group(1));
        return decoded != null && decoded.contains(":");
    }

    private static String decodeBase64Utf8(String encoded) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return null;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
