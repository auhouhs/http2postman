/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.cli;

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
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.PrintStream;
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
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Command-line entry point: converts {@code .http} / {@code .rest} files into a
 * Postman Collection v2.1 JSON file.
 *
 * <p>Exit codes: {@code 0} success, {@code 1} conversion/validation failure,
 * {@code 2} usage error.
 */
public final class Cli {

    public static final long SPLIT_THRESHOLD_BYTES = 2L * 1024 * 1024;

    private static final String USAGE = String.join("\n",
            "usage: http2postman [-h] [-o OUTPUT] [--quiet] [--name NAME]",
            "                     [--env-file PATH] [--prefer-env NAME]",
            "                     PATH [PATH ...]");

    private Cli() {
    }

    /** Run the CLI and return its process exit code. */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        for (String arg : argv) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                out.println(USAGE);
                return 0;
            }
        }

        Options options;
        try {
            options = parseOptions(argv);
        } catch (UsageException e) {
            err.println(USAGE);
            err.println("http2postman: error: " + e.getMessage());
            return 2;
        }

        List<String> files;
        try {
            files = collectSourceFiles(options.paths());
        } catch (UsageException e) {
            err.println(USAGE);
            err.println("http2postman: error: " + e.getMessage());
            return 2;
        } catch (PathException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
        if (files.isEmpty()) {
            err.println("error: no .http or .rest files found");
            return 1;
        }

        Path outputDir = outputDir(options.output(), options.paths());
        Path envFile = options.envFile() == null ? null : Path.of(options.envFile());
        List<SourceDoc> sourceDocs = new ArrayList<>();
        try {
            for (String file : files) {
                sourceDocs.add(HttpFileParser.parseFile(Path.of(file), outputDir, envFile));
            }
        } catch (InvalidEncodingException e) {
            err.println("error: input is not valid UTF-8: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("error: cannot read input: " + e.getMessage());
            return 1;
        }

        String collectionName = options.name() != null
                ? options.name()
                : Names.titleCase(Names.stripExtension(Names.baseName(files.get(0))));

        List<UnsupportedItem> warnings = new ArrayList<>();
        for (SourceDoc doc : sourceDocs) {
            warnings.addAll(doc.warnings());
        }

        Map<String, String> envDefaults = null;
        if (options.preferEnv() != null) {
            Path firstDir = absoluteParent(files.get(0));
            try {
                envDefaults = HttpClientEnv.load(firstDir, options.preferEnv());
            } catch (EnvFileException e) {
                err.println("error: " + e.getMessage());
                return 1;
            }
        }

        PostmanCollection collection = PostmanConverter.convert(
                sourceDocs, collectionName, files, warnings, envDefaults);

        CollectionValidator.Result validation = CollectionValidator.validate(collection);
        if (!validation.isValid()) {
            for (String finding : validation.findings()) {
                err.println("error: validation: " + finding);
            }
            return 1;
        }

        JsonNode tree = PostmanJson.toTree(collection);
        String serialized;
        try {
            serialized = PostmanJson.toPrettyString(tree);
        } catch (JsonProcessingException e) {
            err.println("error: cannot serialize collection: " + e.getMessage());
            return 1;
        }
        long size = serialized.getBytes(StandardCharsets.UTF_8).length;

        try {
            if (size > SPLIT_THRESHOLD_BYTES && files.size() > 1) {
                return writeSplitCollections(options, files, sourceDocs, warnings, envDefaults, out);
            }
            String outputPath = resolveOutputPath(options.output(), options.paths());
            writeAtomic(Path.of(outputPath), serialized);
            printReport(options, collection, outputPath, warnings, size, countScripts(sourceDocs), out);
        } catch (IOException e) {
            err.println("error: cannot write output: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // options
    // ------------------------------------------------------------------

    private record Options(List<String> paths, String output, boolean quiet, String name,
                           String envFile, String preferEnv) {
    }

    private static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    private static final class PathException extends Exception {
        PathException(String message) {
            super(message);
        }
    }

    private static Options parseOptions(String[] argv) throws UsageException {
        List<String> paths = new ArrayList<>();
        String output = null;
        String name = null;
        String envFile = null;
        String preferEnv = null;
        boolean quiet = false;

        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            switch (arg) {
                case "-o", "--output" -> {
                    output = requireValue(argv, i + 1, arg);
                    i++;
                }
                case "--name" -> {
                    name = requireValue(argv, i + 1, arg);
                    i++;
                }
                case "--env-file" -> {
                    envFile = requireValue(argv, i + 1, arg);
                    i++;
                }
                case "--prefer-env" -> {
                    preferEnv = requireValue(argv, i + 1, arg);
                    i++;
                }
                case "--quiet" -> quiet = true;
                default -> {
                    if (arg.startsWith("-")) {
                        throw new UsageException("unrecognized arguments: " + arg);
                    }
                    paths.add(arg);
                }
            }
        }
        if (paths.isEmpty()) {
            throw new UsageException("the following arguments are required: PATH");
        }
        return new Options(List.copyOf(paths), output, quiet, name, envFile, preferEnv);
    }

    private static String requireValue(String[] argv, int index, String flag) throws UsageException {
        if (index >= argv.length) {
            throw new UsageException("argument " + flag + ": expected one argument");
        }
        return argv[index];
    }

    // ------------------------------------------------------------------
    // input discovery
    // ------------------------------------------------------------------

    private static List<String> collectSourceFiles(List<String> paths) throws UsageException, PathException {
        List<String> files = new ArrayList<>();
        for (String path : paths) {
            Path candidate = Path.of(path);
            if (!Files.exists(candidate)) {
                throw new PathException("path does not exist: " + path);
            }
            if (Files.isDirectory(candidate)) {
                walk(candidate, files);
            } else if (Files.isRegularFile(candidate)) {
                if (!isHttpFile(path)) {
                    throw new UsageException("not a .http/.rest file: " + path);
                }
                files.add(path);
            } else {
                throw new UsageException("not a regular file or directory: " + path);
            }
        }
        return files;
    }

    /** Recursive scan: files of a directory first, then its sub-directories. */
    private static void walk(Path directory, List<String> files) throws PathException {
        List<Path> children;
        try (Stream<Path> listing = Files.list(directory)) {
            children = listing.sorted(Comparator.comparing(entry -> entry.getFileName().toString())).toList();
        } catch (IOException e) {
            throw new PathException("cannot read directory: " + e.getMessage());
        }
        for (Path child : children) {
            if (Files.isRegularFile(child)) {
                String name = child.getFileName().toString();
                if (isHttpFile(name)) {
                    files.add(child.toString());
                }
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

    private static Path outputDir(String output, List<String> paths) {
        if (output != null) {
            return absoluteParent(output);
        }
        if (paths.size() == 1 && Files.isRegularFile(Path.of(paths.get(0)))) {
            return absoluteParent(paths.get(0));
        }
        return Path.of("").toAbsolutePath();
    }

    private static String resolveOutputPath(String output, List<String> paths) {
        if (output != null) {
            return output;
        }
        if (paths.size() == 1 && Files.isRegularFile(Path.of(paths.get(0)))) {
            return absoluteParent(paths.get(0)).resolve("postman_collection.json").toString();
        }
        return Path.of("").toAbsolutePath().resolve("postman_collection.json").toString();
    }

    private static Path absoluteParent(String path) {
        Path parent = Path.of(path).toAbsolutePath().getParent();
        return parent != null ? parent : Path.of("").toAbsolutePath();
    }

    // ------------------------------------------------------------------
    // output
    // ------------------------------------------------------------------

    private record SplitOutput(String path, PostmanCollection collection, long size, SourceDoc doc) {
    }

    private static int writeSplitCollections(Options options, List<String> files, List<SourceDoc> sourceDocs,
                                             List<UnsupportedItem> warnings, Map<String, String> envDefaults,
                                             PrintStream out) throws IOException {
        String base = options.output() != null
                ? options.output()
                : Path.of("").toAbsolutePath().resolve("postman_collection.json").toString();
        String stem = Names.stripExtension(base);
        Set<String> used = new LinkedHashSet<>();
        List<SplitOutput> written = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            String path = files.get(i);
            String name = options.name() != null
                    ? options.name()
                    : Names.titleCase(Names.stripExtension(Names.baseName(path)));
            PostmanCollection collection = PostmanConverter.convert(
                    List.of(sourceDocs.get(i)), name, List.of(path), warnings, envDefaults);
            String outPath = splitOutputName(stem, path, used);
            String serialized = PostmanJson.toPrettyString(PostmanJson.toTree(collection));
            writeAtomic(Path.of(outPath), serialized);
            written.add(new SplitOutput(outPath, collection,
                    serialized.getBytes(StandardCharsets.UTF_8).length, sourceDocs.get(i)));
        }

        if (!options.quiet()) {
            if (envDefaults != null) {
                out.println("Env: " + options.preferEnv());
            }
            for (SplitOutput split : written) {
                out.println("Generated: " + split.path());
                out.println("Requests: " + countRequests(split.collection())
                        + " | Folders: " + countFolders(split.collection())
                        + " | Variables: " + split.collection().variable().size()
                        + " | Scripts: " + countScripts(List.of(split.doc())));
                if (split.size() > SPLIT_THRESHOLD_BYTES) {
                    out.println("Warning: " + split.path() + " exceeds 2 MB");
                }
            }
            printWarnings(warnings, out);
            out.println("Collection exceeded " + SPLIT_THRESHOLD_BYTES
                    + " bytes; split into per-source files");
        }
        return 0;
    }

    private static String splitOutputName(String stem, String path, Set<String> used) {
        String base = Names.stripExtension(Names.baseName(path));
        String candidate = stem + "-" + base + ".json";
        if (used.add(candidate)) {
            return candidate;
        }
        int n = 2;
        while (used.contains(stem + "-" + base + "-" + n + ".json")) {
            n++;
        }
        candidate = stem + "-" + base + "-" + n + ".json";
        used.add(candidate);
        return candidate;
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

    // ------------------------------------------------------------------
    // reporting
    // ------------------------------------------------------------------

    private static void printReport(Options options, PostmanCollection collection, String outputPath,
                                    List<UnsupportedItem> warnings, long size, int scripts, PrintStream out) {
        if (options.quiet()) {
            return;
        }
        int requests = countRequests(collection);
        out.println("Generated: " + outputPath);
        out.println("Requests: " + requests
                + " | Folders: " + countFolders(collection)
                + " | Variables: " + collection.variable().size()
                + " | Scripts: " + scripts);
        if (options.preferEnv() != null) {
            out.println("Env: " + options.preferEnv());
        }
        printWarnings(warnings, out);
        if (requests == 0) {
            out.println("Warning: no requests parsed; empty collection generated");
        }
        if (size > SPLIT_THRESHOLD_BYTES) {
            out.println("Warning: collection exceeds 2 MB; consider splitting sources");
        }
    }

    private static void printWarnings(List<UnsupportedItem> warnings, PrintStream out) {
        Map<String, List<UnsupportedItem>> byKind = new TreeMap<>();
        for (UnsupportedItem warning : warnings) {
            byKind.computeIfAbsent(warning.kind().wireName(), kind -> new ArrayList<>()).add(warning);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<UnsupportedItem>> entry : byKind.entrySet()) {
            String locations = entry.getValue().stream()
                    .map(warning -> warning.sourceFile() + ":" + warning.sourceLine())
                    .collect(Collectors.joining(", "));
            switch (entry.getKey()) {
                case "websocket_request" -> lines.add("websocket requests at " + locations);
                case "grpc_request" -> lines.add("grpc requests at " + locations);
                case "dynamic_variable" -> lines.add("unmapped dynamic vars: " + locations);
                case "dotenv" -> lines.add("unresolved dotenv refs at " + locations);
                default -> lines.add(entry.getKey() + ": " + locations);
            }
        }
        if (!lines.isEmpty()) {
            out.println("Unsupported: " + String.join("; ", lines));
        }
    }

    private static int countRequests(PostmanCollection collection) {
        int count = 0;
        for (PostmanItem item : collection.item()) {
            count += countRequestItems(item);
        }
        return count;
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

    private static int countFolders(PostmanCollection collection) {
        int count = 0;
        for (PostmanItem item : collection.item()) {
            count += countFolderItems(item);
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

    private static int countScripts(List<SourceDoc> docs) {
        int count = 0;
        for (SourceDoc doc : docs) {
            for (DocItem item : doc.items()) {
                count += countScripts(item);
            }
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
