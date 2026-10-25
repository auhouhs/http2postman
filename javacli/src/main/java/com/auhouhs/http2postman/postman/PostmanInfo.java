/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Collection metadata ({@code info} object of the v2.1 schema). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostmanInfo(String name, String description, String schema) {
}
