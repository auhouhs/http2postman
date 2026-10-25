/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Postman's decomposed URL form. {@code raw} always carries the complete URL;
 * the remaining components are the best-effort split Postman uses for editing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostmanUrl(
        String raw,
        String protocol,
        List<String> host,
        String port,
        List<String> path,
        List<PostmanKeyValue> query) {

    public static PostmanUrl of(String raw) {
        return new PostmanUrl(raw, null, null, null, null, null);
    }
}
