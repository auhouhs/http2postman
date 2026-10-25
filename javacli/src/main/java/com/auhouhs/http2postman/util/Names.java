/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.util;

import java.nio.file.Path;

/** File and title-casing helpers shared by the converter and the CLI. */
public final class Names {

    private Names() {
    }

    /** {@code "my-api_file"} -> {@code "My Api File"}. */
    public static String titleCase(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (String word : name.split("[-_ ]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** {@code os.path.splitext(name)[0]} for a bare file name or a full path. */
    public static String stripExtension(String path) {
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return dot > separator ? path.substring(0, dot) : path;
    }

    /** Last path component, {@code ""} when the path has none. */
    public static String baseName(String path) {
        Path fileName = Path.of(path).getFileName();
        return fileName == null ? "" : fileName.toString();
    }
}
