/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.model;

/**
 * One multipart part: either a text part ({@code value} set) or a file part
 * ({@code filename} set).
 */
public record BodyPart(String name, String value, String filename) {

    public static BodyPart text(String name, String value) {
        return new BodyPart(name, value, null);
    }

    public static BodyPart file(String name, String filename) {
        return new BodyPart(name, null, filename);
    }

    public boolean isFile() {
        return filename != null;
    }
}
