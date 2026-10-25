/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.model;

import java.util.List;

/** Parsed request body; only the fields belonging to {@link Kind} are set. */
public final class Body {

    public enum Kind {
        RAW,
        FORM_URLENCODED,
        MULTIPART,
        FILE
    }

    private final Kind kind;
    private final String content;
    private final List<FormField> form;
    private final List<BodyPart> parts;
    private final String fileSrc;

    private Body(Kind kind, String content, List<FormField> form, List<BodyPart> parts, String fileSrc) {
        this.kind = kind;
        this.content = content;
        this.form = form;
        this.parts = parts;
        this.fileSrc = fileSrc;
    }

    public static Body raw(String content) {
        return new Body(Kind.RAW, content, null, null, null);
    }

    public static Body formUrlencoded(List<FormField> form) {
        return new Body(Kind.FORM_URLENCODED, null, List.copyOf(form), null, null);
    }

    public static Body multipart(List<BodyPart> parts) {
        return new Body(Kind.MULTIPART, null, null, List.copyOf(parts), null);
    }

    public static Body file(String fileSrc) {
        return new Body(Kind.FILE, null, null, null, fileSrc);
    }

    public Kind kind() {
        return kind;
    }

    /** Raw body text, {@code null} for every other kind. */
    public String content() {
        return content;
    }

    /** Urlencoded pairs, empty for every other kind. */
    public List<FormField> form() {
        return form == null ? List.of() : form;
    }

    /** Multipart parts, empty for every other kind. */
    public List<BodyPart> parts() {
        return parts == null ? List.of() : parts;
    }

    /** Referenced file path, {@code null} for every other kind. */
    public String fileSrc() {
        return fileSrc;
    }
}
