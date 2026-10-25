/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

/** A Postman collection-level script event. */
public record PostmanEvent(String listen, PostmanScript script) {

    public static PostmanEvent preRequest(PostmanScript script) {
        return new PostmanEvent("prerequest", script);
    }

    public static PostmanEvent test(PostmanScript script) {
        return new PostmanEvent("test", script);
    }
}
