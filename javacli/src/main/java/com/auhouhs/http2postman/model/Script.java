/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.model;

import java.util.List;

/**
 * A captured {@code < {% ... %}} pre-request or {@code > {% ... %}} response
 * script block; {@code lines} holds the raw JS source without the wrappers.
 */
public record Script(Kind kind, List<String> lines) {

    public enum Kind {
        PREREQUEST("prerequest"),
        TEST("test");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public Script {
        lines = List.copyOf(lines);
    }
}
