/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.converter;

import com.auhouhs.http2postman.model.Body;
import com.auhouhs.http2postman.model.FormField;
import com.auhouhs.http2postman.model.Header;
import com.auhouhs.http2postman.model.Request;
import com.auhouhs.http2postman.model.SourceDoc;
import com.auhouhs.http2postman.parser.HttpFileParser;
import com.auhouhs.http2postman.postman.PostmanAuth;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.auhouhs.http2postman.TestSupport.texts;
import static com.auhouhs.http2postman.TestSupport.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Auth detection tests: OAuth2 client credentials, Bearer, Basic. */
class AuthDetectorTest {

    private static Request makeRequest(String url, String method, List<Header> headers, Body body) {
        return new Request("r", method, url, headers, body, "f.http", 1);
    }

    private static Request makeRequest(String url, String method, List<Header> headers) {
        return makeRequest(url, method, headers, null);
    }

    private static Body formBody(String... pairs) {
        List<FormField> fields = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.add(new FormField(pairs[i], pairs[i + 1]));
        }
        return Body.formUrlencoded(fields);
    }

    private static String basicCredentials(String user, String password) {
        return Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode authJson(PostmanAuth auth) {
        return tree(auth);
    }

    private static Map<String, String> authValues(JsonNode auth, String type) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode parameter : auth.get(type)) {
            values.put(parameter.get("key").asText(), parameter.get("value").asText());
        }
        return values;
    }

    @Test
    void tokenRequestGetsOauth2Auth() {
        Request request = makeRequest(
                "https://identity.example.com/oauth2/token",
                "POST",
                List.of(new Header("Authorization", "Basic " + basicCredentials("client-1", "secret-1"))),
                formBody("grant_type", "client_credentials", "scope", "api"));
        JsonNode auth = authJson(AuthDetector.authForRequest(request));
        assertNotNull(auth);
        assertEquals("oauth2", auth.get("type").asText());
        Map<String, String> values = authValues(auth, "oauth2");
        assertEquals("client_credentials", values.get("grantType"));
        assertEquals("https://identity.example.com/oauth2/token", values.get("accessTokenUrl"));
        assertEquals("client-1", values.get("clientId"));
        assertEquals("secret-1", values.get("clientSecret"));
        assertEquals("header", values.get("addTokenTo"));
    }

    @Test
    void variableWrappedBasicNeverTriggers() {
        Request request = makeRequest(
                "https://identity.example.com/token",
                "POST",
                List.of(new Header("Authorization", "Basic {{brokerBasicAuth}}")),
                formBody("grant_type", "client_credentials"));
        assertNull(AuthDetector.authForRequest(request));
    }

    @Test
    void missingBasicNeverTriggers() {
        Request request = makeRequest(
                "https://identity.example.com/login",
                "POST",
                List.of(),
                formBody("grant_type", "client_credentials"));
        assertNull(AuthDetector.authForRequest(request));
    }

    @Test
    void wrongMethodNeverTriggersOauth2() {
        Request request = makeRequest(
                "https://identity.example.com/token",
                "GET",
                List.of(new Header("Authorization", "Basic " + basicCredentials("c", "s"))),
                formBody("grant_type", "client_credentials"));
        JsonNode auth = authJson(AuthDetector.authForRequest(request));
        assertNotNull(auth);
        assertNotEquals("oauth2", auth.get("type").asText());
    }

    @Test
    void wrongGrantTypeNeverTriggersOauth2() {
        Request request = makeRequest(
                "https://identity.example.com/token",
                "POST",
                List.of(new Header("Authorization", "Basic " + basicCredentials("c", "s"))),
                formBody("grant_type", "authorization_code"));
        JsonNode auth = authJson(AuthDetector.authForRequest(request));
        assertNotNull(auth);
        assertNotEquals("oauth2", auth.get("type").asText());
    }

    @Test
    void undecodableBasicNeverTriggers() {
        Request request = makeRequest(
                "https://identity.example.com/token",
                "POST",
                List.of(new Header("Authorization", "Basic not-base64!!!")),
                formBody("grant_type", "client_credentials"));
        assertNull(AuthDetector.authForRequest(request));
    }

    @Test
    void bearerTokenRequest() {
        Request request = makeRequest(
                "https://api.example.com/users",
                "GET",
                List.of(new Header("Authorization", "Bearer {{brokerAccessToken}}")));
        JsonNode auth = authJson(AuthDetector.authForRequest(request));
        assertEquals("bearer", auth.get("type").asText());
        assertEquals("[{\"key\":\"token\",\"value\":\"{{brokerAccessToken}}\",\"type\":\"string\"}]",
                auth.get("bearer").toString());
    }

    @Test
    void bearerMultiwordNeverTriggers() {
        Request request = makeRequest(
                "https://api.example.com/users",
                "GET",
                List.of(new Header("Authorization", "Bearer abc def")));
        assertNull(AuthDetector.authForRequest(request));
    }

    @Test
    void lowercaseSchemes() {
        Request bearer = makeRequest(
                "https://api.example.com/users",
                "GET",
                List.of(new Header("Authorization", "bearer {{tok}}")));
        assertEquals("bearer", authJson(AuthDetector.authForRequest(bearer)).get("type").asText());

        Request basic = makeRequest(
                "https://api.example.com/x",
                "GET",
                List.of(new Header("Authorization", "basic {{user}} {{pass}}")));
        assertEquals("basic", authJson(AuthDetector.authForRequest(basic)).get("type").asText());
    }

    @Test
    void bearerLiteralNeverTriggers() {
        Request request = makeRequest(
                "https://api.example.com/users",
                "GET",
                List.of(new Header("Authorization", "Bearer abc123")));
        assertNull(AuthDetector.authForRequest(request));
    }

    @Test
    void literalBase64Basic() {
        Request request = makeRequest(
                "https://api.example.com/x",
                "GET",
                List.of(new Header("Authorization", "Basic " + basicCredentials("alice", "pa$$"))));
        JsonNode auth = authJson(AuthDetector.authForRequest(request));
        assertEquals("basic", auth.get("type").asText());
        Map<String, String> values = authValues(auth, "basic");
        assertEquals("alice", values.get("username"));
        assertEquals("pa$$", values.get("password"));
    }

    @Test
    void basicTwoVariableForm() {
        Request request = makeRequest(
                "https://api.example.com/x",
                "GET",
                List.of(new Header("Authorization", "Basic {{username}} {{password}}")));
        JsonNode auth = authJson(AuthDetector.authForRequest(request));
        assertEquals("basic", auth.get("type").asText());
        Map<String, String> values = authValues(auth, "basic");
        assertEquals("{{username}}", values.get("username"));
        assertEquals("{{password}}", values.get("password"));
    }

    @Test
    void basicSingleVariableKeptVerbatim() {
        Request request = makeRequest(
                "https://api.example.com/x",
                "GET",
                List.of(new Header("Authorization", "Basic {{brokerBasicAuth}}")));
        assertNull(AuthDetector.authForRequest(request));
    }

    @Test
    void undecodableLiteralKept() {
        Request request = makeRequest(
                "https://api.example.com/x",
                "GET",
                List.of(new Header("Authorization", "Basic !!!")));
        assertNull(AuthDetector.authForRequest(request));
    }

    @Test
    void headerRemovedWhenAuthEmitted() {
        SourceDoc doc = HttpFileParser.parseLines(
                List.of("### r", "GET http://x.test/a", "Authorization: Bearer {{token}}"));
        JsonNode item = tree(PostmanConverter.convert(List.of(doc), "t")).get("item").get(0);
        assertEquals("bearer", item.get("auth").get("type").asText());
        assertFalse(texts(item.get("request").get("header")).contains("Authorization"));
    }

    @Test
    void noAuthKeyForPlainRequests() {
        SourceDoc doc = HttpFileParser.parseLines(List.of("### r", "GET http://x.test/a"));
        JsonNode item = tree(PostmanConverter.convert(List.of(doc), "t")).get("item").get(0);
        assertFalse(item.has("auth"));
    }

    @Test
    void bearerViaTokenVariableKeptVerbatim() {
        SourceDoc doc = HttpFileParser.parseLines(
                List.of("### r", "GET http://x.test/a", "Authorization: {{brokerAuth}}"));
        JsonNode item = tree(PostmanConverter.convert(List.of(doc), "t")).get("item").get(0);
        assertFalse(item.has("auth"));
        assertEquals("[{\"key\":\"Authorization\",\"value\":\"{{brokerAuth}}\"}]",
                item.get("request").get("header").toString());
    }
}
