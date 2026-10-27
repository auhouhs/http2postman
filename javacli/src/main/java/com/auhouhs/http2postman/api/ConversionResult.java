/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.api;

import com.auhouhs.http2postman.model.UnsupportedItem;

import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of a successful conversion; mirrors the report printed by the CLI.
 *
 * @param environment environment baked in as collection-variable defaults, {@code null} when unused
 * @param warnings    anything Postman cannot express faithfully (never silently dropped)
 */
public record ConversionResult(Path outputFile, String collectionName, String environment,
                               int requests, int folders, int variables, int scripts,
                               long sizeBytes, List<UnsupportedItem> warnings) {

    public ConversionResult {
        warnings = List.copyOf(warnings);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
