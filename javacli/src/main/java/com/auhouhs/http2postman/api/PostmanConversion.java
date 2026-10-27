/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.api;

import com.auhouhs.http2postman.converter.PostmanConverter;
import com.auhouhs.http2postman.env.EnvFileException;
import com.auhouhs.http2postman.env.HttpClientEnv;
import com.auhouhs.http2postman.model.DocItem;
import com.auhouhs.http2postman.model.Folder;
import com.auhouhs.http2postman.model.Request;
import com.auhouhs.http2postman.model.SourceDoc;
import com.auhouhs.http2postman.model.UnsupportedItem;
import com.auhouhs.http2postman.parser.HttpFileParser;
import com.auhouhs.http2postman.parser.InvalidEncodingException;
import com.auhouhs.http2postman.postman.PostmanCollection;
import com.auhouhs.http2postman.postman.PostmanItem;
import com.auhouhs.http2postman.postman.PostmanJson;
import com.auhouhs.http2postman.util.Names;
import com.auhouhs.http2postman.validation.CollectionValidator;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * In-process entry point for hosts embedding the converter (the IntelliJ plugin).
 *
 * <p>Same behaviour as {@code com.auhouhs.http2postman.cli.Cli}, but nothing is
 * printed and no exit code is produced: failures surface as
 * {@link ConversionException} and the outcome is returned as
 * {@link ConversionResult}.
 */
public final class PostmanConversion {

    private PostmanConversion() {
    }

    /**
     * Convert {@code request.sources} into a Postman Collection v2.1 file.
     *
     * <p>Source files are never modified; the output directory is created when
     * missing and the collection is written atomically.
     */
    public static ConversionResult convert(ConversionRequest request) throws ConversionException {
        List<String> files = collectSources(request.sources());
        if (files.isEmpty()) {
            throw new ConversionException("no .http or .rest files found");
        }

        Path outputFile = request.outputFile().toAbsolutePath().normalize();
        Path outputDir = outputFile.getParent() != null
                ? outputFile.getParent()
                : Path.of("").toAbsolutePath();
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new ConversionException("cannot create output directory " + outputDir + ": "
                    + e.getMessage(), e);
        }

        List<SourceDoc> sourceDocs = new ArrayList<>();
        try {
            for (String file : files) {
                sourceDocs.add(HttpFileParser.parseFile(Path.of(file), outputDir, request.envFile()));
            }
        } catch (InvalidEncodingException e) {
            throw new ConversionException("input is not valid UTF-8: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ConversionException("cannot read input: " + e.getMessage(), e);
        }

        String collectionName = request.collectionName() == null || request.collectionName().isBlank()
                ? Names.titleCase(Names.stripExtension(Names.baseName(files.get(0))))
                : request.collectionName().strip();

        List<UnsupportedItem> warnings = new ArrayList<>();
        for (SourceDoc doc : sourceDocs) {
            warnings.addAll(doc.warnings());
        }

        String environment = request.environment() == null || request.environment().isBlank()
                ? null
                : request.environment().strip();
        Map<String, String> envDefaults = null;
        if (environment != null) {
            try {
                envDefaults = HttpClientEnv.load(absoluteParent(files.get(0)), environment);
            } catch (EnvFileException e) {
                throw new ConversionException(e.getMessage(), e);
            }
        }

        PostmanCollection collection = PostmanConverter.convert(
                sourceDocs, collectionName, files, warnings, envDefaults);

        CollectionValidator.Result validation = CollectionValidator.validate(collection);
        if (!validation.isValid()) {
            throw new ConversionException("validation failed: " + String.join("; ", validation.findings()));
        }

        String serialized;
        try {
            serialized = PostmanJson.toPrettyString(PostmanJson.toTree(collection));
        } catch (JsonProcessingException e) {
            throw new ConversionException("cannot serialize collection: " + e.getMessage(), e);
        }
        try {
            writeAtomic(outputFile, serialized);
        } catch (IOException e) {
            throw new ConversionException("cannot write " + outputFile + ": " + e.getMessage(), e);
        }

        return new ConversionResult(outputFile, collectionName, environment,
                countRequests(collection), countFolders(collection), collection.variable().size(),
                countScripts(sourceDocs), serialized.getBytes(StandardCharsets.UTF_8).length, warnings);
    }

    /**
     * Environment names declared in {@code http-client.env.json} /
     * {@code http-client.private.env.json} inside {@code directory}, sorted.
     *
     * <p>Unreadable or invalid files simply yield an empty list.
     */
    public static List<String> environmentNames(Path directory) {
        try {
            return HttpClientEnv.names(directory);
        } catch (EnvFileException e) {
            return List.of();
        }
    }

    /** Number of request items in the collection, folders included. */
    public static int countRequests(PostmanCollection collection) {
        int count = 0;
        for (PostmanItem item : collection.item()) {
            count += countRequestItems(item);
        }
        return count;
    }

    /** Number of folders in the collection, nested ones included. */
    public static int countFolders(PostmanCollection collection) {
        int count = 0;
        for (PostmanItem item : collection.item()) {
            count += countFolderItems(item);
        }
        return count;
    }

    /** Number of translated pre-request and test scripts in the parsed sources. */
    public static int countScripts(List<SourceDoc> docs) {
        int count = 0;
        for (SourceDoc doc : docs) {
            for (DocItem item : doc.items()) {
                count += countScripts(item);
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private static List<String> collectSources(List<Path> sources) throws ConversionException {
        Set<String> files = new LinkedHashSet<>();
        for (Path source : sources) {
            Path candidate = source.toAbsolutePath().normalize();
            if (Files.isDirectory(candidate)) {
                walk(candidate, files);
            } else if (Files.isRegularFile(candidate)) {
                if (!isHttpFile(candidate.getFileName().toString())) {
                    throw new ConversionException("not a .http/.rest file: " + candidate);
                }
                files.add(candidate.toString());
            } else {
                throw new ConversionException("file does not exist: " + candidate);
            }
        }
        return new ArrayList<>(files);
    }

    /** Recursive scan: files of a directory first, then its sub-directories. */
    private static void walk(Path directory, Set<String> files) throws ConversionException {
        List<Path> children;
        try (Stream<Path> listing = Files.list(directory)) {
            children = listing.sorted(Comparator.comparing(entry -> entry.getFileName().toString())).toList();
        } catch (IOException e) {
            throw new ConversionException("cannot read directory " + directory + ": " + e.getMessage(), e);
        }
        for (Path child : children) {
            if (Files.isRegularFile(child) && isHttpFile(child.getFileName().toString())) {
                files.add(child.toString());
            }
        }
        for (Path child : children) {
            if (Files.isDirectory(child)) {
                walk(child, files);
            }
        }
    }

    private static boolean isHttpFile(String name) {
        return name.endsWith(".http") || name.endsWith(".rest");
    }

    private static Path absoluteParent(String path) {
        Path parent = Path.of(path).toAbsolutePath().getParent();
        return parent != null ? parent : Path.of("").toAbsolutePath();
    }

    /** Write atomically: temp file in the target directory, then rename. */
    private static void writeAtomic(Path outputPath, String serialized) throws IOException {
        Path target = outputPath.toAbsolutePath();
        Path directory = target.getParent();
        Path temp = Files.createTempFile(directory, ".http2postman-", ".tmp");
        try {
            byte[] data = serialized.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(data));
                channel.force(true);
            }
            try {
                Files.move(temp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static int countRequestItems(PostmanItem item) {
        if (item.request() != null) {
            return 1;
        }
        int count = 0;
        if (item.item() != null) {
            for (PostmanItem child : item.item()) {
                count += countRequestItems(child);
            }
        }
        return count;
    }

    private static int countFolderItems(PostmanItem item) {
        if (item.item() == null) {
            return 0;
        }
        int count = 1;
        for (PostmanItem child : item.item()) {
            count += countFolderItems(child);
        }
        return count;
    }

    private static int countScripts(DocItem item) {
        if (item instanceof Request request) {
            return request.preScripts().size() + request.postScripts().size();
        }
        if (item instanceof Folder folder) {
            int count = 0;
            for (DocItem child : folder.items()) {
                count += countScripts(child);
            }
            return count;
        }
        return 0;
    }
}
