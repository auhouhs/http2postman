/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Percent-decoding helpers matching Python's {@code urllib.parse.unquote/unquote_plus}. */
public final class PercentCodec {

    private PercentCodec() {
    }

    /** Like Python's {@code unquote_plus}: {@code +} becomes a space, then percent-decoding. */
    public static String decodePlus(String text) {
        return percentDecode(text.replace('+', ' '));
    }

    /**
     * Decode {@code %XX} UTF-8 sequences; malformed sequences are kept literally
     * and invalid byte runs become the Unicode replacement character.
     */
    public static String percentDecode(String text) {
        StringBuilder out = new StringBuilder(text.length());
        ByteArrayOutputStream bytes = null;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < n && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2))) {
                if (bytes == null) {
                    bytes = new ByteArrayOutputStream();
                }
                bytes.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                i += 3;
                continue;
            }
            if (bytes != null) {
                out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                bytes = null;
            }
            out.append(c);
            i++;
        }
        if (bytes != null) {
            out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
