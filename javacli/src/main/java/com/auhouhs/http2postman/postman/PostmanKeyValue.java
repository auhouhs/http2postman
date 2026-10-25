/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

/** A {@code {"key": ..., "value": ...}} pair, used for headers, variables and query params. */
public record PostmanKeyValue(String key, String value) {
}
