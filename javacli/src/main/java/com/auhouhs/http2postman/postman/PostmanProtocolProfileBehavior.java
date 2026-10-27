/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Sending tweaks stored next to the request; only the pinned HTTP version is emitted. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostmanProtocolProfileBehavior(String protocolVersion) {
}
