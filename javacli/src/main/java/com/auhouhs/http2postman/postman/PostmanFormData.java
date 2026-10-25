/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One {@code formdata} field: a text value or a file source. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostmanFormData(String key, String type, String value, String src) {

    public static PostmanFormData text(String key, String value) {
        return new PostmanFormData(key, "text", value, null);
    }

    public static PostmanFormData file(String key, String src) {
        return new PostmanFormData(key, "file", null, src);
    }
}
