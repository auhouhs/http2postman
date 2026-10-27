/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.model;

/**
 * HTTP version pinned after the request URL ({@code GET https://example.org HTTP/2}).
 * Postman has no prior-knowledge switch, so both HTTP/2 variants map to {@code http2}.
 */
public enum HttpVersion {
    HTTP_1_1("http1"),
    HTTP_2("http2"),
    HTTP_2_PRIOR_KNOWLEDGE("http2");

    private final String postmanValue;

    HttpVersion(String postmanValue) {
        this.postmanValue = postmanValue;
    }

    /** Value for {@code protocolProfileBehavior.protocolVersion}. */
    public String postmanValue() {
        return postmanValue;
    }
}
