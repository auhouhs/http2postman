/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.api;

import java.nio.file.Path;
import java.util.List;

/**
 * Input of a programmatic conversion; the counterpart of the CLI arguments.
 *
 * @param sources        {@code .http} / {@code .rest} files or directories to scan recursively
 * @param outputFile     target JSON file (its directory is created when missing)
 * @param collectionName Postman collection name; {@code null} derives it from the first source
 * @param envFile        explicit {@code .env} file; {@code null} auto-detects next to each source
 * @param environment    JetBrains HTTP Client environment name; {@code null} disables it
 */
public record ConversionRequest(List<Path> sources, Path outputFile, String collectionName,
                                Path envFile, String environment) {

    public ConversionRequest {
        sources = List.copyOf(sources);
    }
}
