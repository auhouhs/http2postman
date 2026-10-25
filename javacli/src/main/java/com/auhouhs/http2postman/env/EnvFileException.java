/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.env;

/** Raised when {@code http-client.env.json} is missing, unreadable or lacks the environment. */
public final class EnvFileException extends Exception {

    public EnvFileException(String message) {
        super(message);
    }

    public EnvFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
