/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

import java.util.List;

/** The {@code script} object of an event. */
public record PostmanScript(String type, List<String> exec) {

    public static PostmanScript javascript(List<String> exec) {
        return new PostmanScript("text/javascript", List.copyOf(exec));
    }
}
