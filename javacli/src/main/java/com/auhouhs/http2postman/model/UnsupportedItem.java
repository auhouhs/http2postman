/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.model;

/** Something the conversion could not express faithfully; reported, never dropped silently. */
public record UnsupportedItem(Kind kind, String message, String sourceFile, int sourceLine) {

    public enum Kind {
        WEBSOCKET_REQUEST("websocket_request"),
        GRPC_REQUEST("grpc_request"),
        DYNAMIC_VARIABLE("dynamic_variable"),
        DOTENV("dotenv"),
        MULTIPART_NO_BOUNDARY("multipart_no_boundary"),
        MULTIPART_UNTERMINATED("multipart_unterminated"),
        EXTERNAL_REF_UNREADABLE("external_ref_unreadable"),
        RESPONSE_FILE("response_file");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
