/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Request body in exactly one mode; the fields of the other modes stay
 * {@code null} and are omitted from the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostmanBody(
        String mode,
        String raw,
        PostmanRawOptions options,
        List<PostmanKeyValue> urlencoded,
        List<PostmanFormData> formdata,
        PostmanFile file) {
}
