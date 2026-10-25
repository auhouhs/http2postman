/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.model;

/** Anything that can live in a {@link Folder} or a {@link SourceDoc}. */
public sealed interface DocItem permits Request, Folder {
}
