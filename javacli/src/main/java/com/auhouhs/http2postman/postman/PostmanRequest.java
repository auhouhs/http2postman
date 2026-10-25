/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The {@code request} object of a collection item. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostmanRequest(
        String method,
        List<PostmanKeyValue> header,
        PostmanUrl url,
        PostmanBody body) {
}
