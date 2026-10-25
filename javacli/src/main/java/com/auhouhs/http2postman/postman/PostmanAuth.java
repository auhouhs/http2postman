/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Postman {@code auth} object; exactly one of the auth-type lists is set. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostmanAuth(
        String type,
        List<PostmanAuthParam> bearer,
        List<PostmanAuthParam> basic,
        List<PostmanAuthParam> oauth2) {

    public static PostmanAuth bearer(String token) {
        return new PostmanAuth("bearer", List.of(PostmanAuthParam.of("token", token)), null, null);
    }

    public static PostmanAuth basic(String username, String password) {
        return new PostmanAuth("basic", null,
                List.of(PostmanAuthParam.of("username", username), PostmanAuthParam.of("password", password)),
                null);
    }

    public static PostmanAuth oauth2ClientCredentials(String accessTokenUrl, String clientId, String clientSecret) {
        return new PostmanAuth("oauth2", null, null, List.of(
                PostmanAuthParam.of("grantType", "client_credentials"),
                PostmanAuthParam.of("accessTokenUrl", accessTokenUrl),
                PostmanAuthParam.of("clientId", clientId),
                PostmanAuthParam.of("clientSecret", clientSecret),
                PostmanAuthParam.of("addTokenTo", "header")));
    }
}
