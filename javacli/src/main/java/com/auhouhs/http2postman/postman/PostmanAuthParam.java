/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

/** A single typed auth attribute, e.g. {@code {"key": "token", "value": "...", "type": "string"}}. */
public record PostmanAuthParam(String key, String value, String type) {

    public static PostmanAuthParam of(String key, String value) {
        return new PostmanAuthParam(key, value, "string");
    }
}
