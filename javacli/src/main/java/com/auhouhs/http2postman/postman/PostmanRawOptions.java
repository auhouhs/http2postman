/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

/** {@code {"raw": {"language": "json"}}} options block of a raw body. */
public record PostmanRawOptions(Raw raw) {

    public record Raw(String language) {
    }

    public static PostmanRawOptions language(String language) {
        return new PostmanRawOptions(new Raw(language));
    }
}
