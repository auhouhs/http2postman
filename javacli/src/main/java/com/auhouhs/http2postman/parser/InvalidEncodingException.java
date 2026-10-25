/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.parser;

import java.io.IOException;

/** Raised when a source file is not valid UTF-8 (byte-for-byte port of the Python error). */
public final class InvalidEncodingException extends IOException {

    public InvalidEncodingException(String message) {
        super(message);
    }
}
