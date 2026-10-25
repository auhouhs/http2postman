/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Root of a Postman Collection v2.1 document. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostmanCollection(PostmanInfo info, List<PostmanItem> item, List<PostmanKeyValue> variable) {
}
