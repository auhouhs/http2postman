/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.parser;

import com.auhouhs.http2postman.model.Body;
import com.auhouhs.http2postman.model.BodyPart;
import com.auhouhs.http2postman.model.DocItem;
import com.auhouhs.http2postman.model.Folder;
import com.auhouhs.http2postman.model.FormField;
import com.auhouhs.http2postman.model.Header;
import com.auhouhs.http2postman.model.HttpVersion;
import com.auhouhs.http2postman.model.Request;
import com.auhouhs.http2postman.model.Script;
import com.auhouhs.http2postman.model.SourceDoc;
import com.auhouhs.http2postman.model.UnsupportedItem;

import com.auhouhs.http2postman.util.PercentCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line-oriented parser for JetBrains HTTP Client {@code .http} / {@code .rest} files.
 *
 * <p>Invariants inherited from the upstream implementation: sources are only
 * ever opened for reading, and no {@code .http} content is dropped silently.
 */
public final class HttpFileParser {

    private static final Pattern METHOD_TOKEN = Pattern.compile("^[A-Za-z]+$");
    private static final Pattern WS_GRPC = Pattern.compile("^(WEBSOCKET|GRPC)\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> KNOWN_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final List<String> URL_PREFIXES = List.of("http://", "https://", "ws://", "wss://", "{{", "/");
    private static final List<String> REF_PREFIXES = List.of("./", "../", "/", "~", "{{", "$");
    private static final Pattern NAME_DIRECTIVE = Pattern.compile("^#\\s*@name\\s+(.+?)\\s*$");
    static final Pattern HEADER_LINE = Pattern.compile("^([^:\\s][^:]*):\\s*(.*)$");
    private static final Pattern BOUNDARY_PARAM = Pattern.compile("boundary\\s*=\\s*\"?([^\"\\s;]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CD_NAME = Pattern.compile("name\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern CD_FILENAME = Pattern.compile("filename\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM = Pattern.compile("^[^=&\\s]+=[^=&\\s]*(\\s*&\\s*[^=&\\s]+=[^=&\\s]*)*$");
    private static final Pattern DOTENV_TOKEN = Pattern.compile("\\{\\{\\s*\\$dotenv\\s+([^}\\s]+)\\s*\\}\\}");
    private static final Pattern HTTP_VERSION =
            Pattern.compile("\\s+HTTP/(1\\.1|2)(\\s*\\(\\s*Prior\\s+Knowledge\\s*\\))?$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPONSE_FILE_DIRECTIVE = Pattern.compile("^>>!?(\\s+.*)?$");
    private static final Pattern INLINE_RESPONSE_FILE = Pattern.compile("\\s+>>!?(\\s+.*)?$");

    private static final List<String> TEXT_CONTENT_TYPES = List.of(
            "text/",
            "application/json",
            "application/xml",
            "application/xhtml+xml",
            "application/javascript",
            "application/graphql",
            "application/x-www-form-urlencoded");

    private static final String PLACEHOLDER_REF = "(unreadable external reference: %s)";

    private HttpFileParser() {
    }

    /** Parse {@code path}, resolving {@code {{$dotenv VAR}}} from the {@code .env} next to it. */
    public static SourceDoc parseFile(Path path) throws IOException {
        return parseFile(path, null, null);
    }

    /**
     * Parse {@code path}.
     *
     * @param outputDir directory for generated multipart placeholder files
     *                  (defaults to the directory holding the source file)
     * @param envPath   explicit {@code .env} file (defaults to {@code .env} next to the source)
     */
    public static SourceDoc parseFile(Path path, Path outputDir, Path envPath) throws IOException {
        byte[] data = Files.readAllBytes(path);
        String text = decodeStrict(data);
        Path absolute = path.toAbsolutePath();
        Path sourceDir = absolute.getParent() != null ? absolute.getParent() : Path.of(".");
        Path effectiveOutputDir = outputDir != null ? outputDir : sourceDir;
        Path effectiveEnvPath = envPath != null ? envPath : sourceDir.resolve(".env");
        Map<String, String> envVars = readDotenv(effectiveEnvPath);
        try {
            return new ParserRun(envVars).parse(text, path.toString(), effectiveOutputDir);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Parse in-memory lines (test helper). */
    public static SourceDoc parseLines(List<String> lines) {
        return parseLines(lines, "<string>", Path.of("."), Map.of());
    }

    /** Parse in-memory lines with {@code {{$dotenv VAR}}} values supplied directly. */
    public static SourceDoc parseLines(List<String> lines, Map<String, String> envVars) {
        return parseLines(lines, "<string>", Path.of("."), envVars);
    }

    /** Parse in-memory lines (test helper). */
    public static SourceDoc parseLines(List<String> lines, String source, Path outputDir, Map<String, String> envVars) {
        Map<String, String> env = envVars == null ? Map.of() : envVars;
        return new ParserRun(env).parse(String.join("\n", lines) + "\n", source, outputDir);
    }

    // ------------------------------------------------------------------
    // decoding / dotenv
    // ------------------------------------------------------------------

    static String decodeStrict(byte[] data) throws InvalidEncodingException {
        int offset = bomLength(data);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(data, offset, data.length - offset)).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidEncodingException(e.toString());
        }
    }

    private static String decodeLenient(byte[] data) {
        int offset = bomLength(data);
        return new String(data, offset, data.length - offset, StandardCharsets.UTF_8);
    }

    private static int bomLength(byte[] data) {
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            return 3;
        }
        return 0;
    }

    /**
     * Read a {@code .env} file ({@code KEY=VALUE}, {@code #} comments, optional quotes).
     * Returns an empty map when the file is absent or unreadable.
     */
    static Map<String, String> readDotenv(Path path) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
        Map<String, String> env = new LinkedHashMap<>();
        for (String rawLine : decodeLenient(data).split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int eq = line.indexOf('=');
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2
                    && value.charAt(0) == value.charAt(value.length() - 1)
                    && (value.charAt(0) == '\'' || value.charAt(0) == '"')) {
                value = value.substring(1, value.length() - 1);
            }
            env.put(line.substring(0, eq).strip(), value);
        }
        return env;
    }

    // ------------------------------------------------------------------
    // parser run
    // ------------------------------------------------------------------

    private enum State {
        FILE,
        HEADERS,
        BODY,
        AFTER_HANDLER
    }

    private static final class PendingRequest {
        String name = "";
        String method;
        String url;
        boolean urlOpen;
        HttpVersion httpVersion;
        String responseFile;
        int responseFileLine;
        final List<Header> headers = new ArrayList<>();
        final int line;
        final List<String> bodyLines = new ArrayList<>();
        final List<String> directives = new ArrayList<>();
        final List<Script> preScripts = new ArrayList<>();
        String ref;
        final Folder folder;
        String wsGrpcKind;
        String wsGrpcUrl;
        int wsGrpcLine;

        PendingRequest(int line, Folder folder) {
            this.line = line;
            this.folder = folder;
        }
    }

    private record MultipartResult(List<BodyPart> parts, boolean closed) {
    }

    private static final class ParserRun {

        private final Map<String, String> envVars;
        private final SourceDoc doc = new SourceDoc();

        private State state = State.FILE;
        private Folder currentFolder;
        private String pendingName;
        private PendingRequest pending;
        private String preOpener;
        private List<String> preCont = new ArrayList<>();
        private String postOpener;
        private List<String> postCont = new ArrayList<>();
        private Request lastRequest;

        private String source;
        private Path outputDir;

        ParserRun(Map<String, String> envVars) {
            this.envVars = envVars;
        }

        SourceDoc parse(String text, String source, Path outputDir) {
            this.source = source;
            this.outputDir = outputDir;

            String[] lines = text.split("\n", -1);
            for (int k = 0; k < lines.length; k++) {
                while (lines[k].endsWith("\r")) {
                    lines[k] = lines[k].substring(0, lines[k].length() - 1);
                }
            }
            int n = lines.length;
            int i = 0;

            while (i < n) {
                String raw = lines[i];
                int lineNo = i + 1;
                String stripped = raw.strip();

                if (preOpener != null) {
                    preCont.add(raw);
                    if (stripped.endsWith("%}")) {
                        pending.preScripts.add(new Script(Script.Kind.PREREQUEST,
                                extractScriptLines(preOpener, preCont)));
                        preOpener = null;
                    }
                    i++;
                    continue;
                }

                if (state == State.FILE) {
                    if (stripped.startsWith("###")) {
                        pending = startRequest(raw, lineNo);
                        pendingName = null;
                        state = State.HEADERS;
                        i++;
                        continue;
                    }
                    if (applyFileLevel(raw, stripped)) {
                        i++;
                        continue;
                    }
                    pending = startRequest(raw, lineNo);
                    pendingName = null;
                    state = State.HEADERS;
                    continue;
                }

                if (state == State.HEADERS) {
                    if (stripped.startsWith("###")) {
                        finalizeRequest(pending);
                        pending = startRequest(raw, lineNo);
                        pendingName = null;
                        state = State.HEADERS;
                        i++;
                        continue;
                    }
                    if (RESPONSE_FILE_DIRECTIVE.matcher(stripped).matches()) {
                        recordResponseFile(pending, stripped, lineNo);
                        i++;
                        continue;
                    }
                    if (pending.method == null) {
                        if (stripped.startsWith("#")) {
                            Matcher m = NAME_DIRECTIVE.matcher(stripped);
                            if (m.matches()) {
                                pending.name = m.group(1).strip();
                            }
                            i++;
                            continue;
                        }
                        if (stripped.startsWith("//") || stripped.isEmpty()) {
                            i++;
                            continue;
                        }
                        if (isPreRequestStart(stripped)) {
                            preOpener = raw;
                            preCont = new ArrayList<>();
                            if (stripped.endsWith("%}")) {
                                pending.preScripts.add(new Script(Script.Kind.PREREQUEST,
                                        extractScriptLines(raw, List.of())));
                                preOpener = null;
                            }
                            i++;
                            continue;
                        }
                        if (stripped.startsWith(">")) {
                            i++;
                            continue;
                        }
                        if (stripped.startsWith("@")) {
                            if (raw.contains("=")) {
                                applyVariable(raw);
                            } else if (stripped.startsWith("@group")) {
                                String name = stripped.substring("@group".length()).strip();
                                if (!name.isEmpty()) {
                                    openFolder(name);
                                }
                            }
                            i++;
                            continue;
                        }
                        Matcher ws = WS_GRPC.matcher(stripped);
                        if (ws.matches()) {
                            pending.wsGrpcKind = ws.group(1).toUpperCase(Locale.ROOT);
                            pending.wsGrpcUrl = ws.group(2);
                            pending.wsGrpcLine = lineNo;
                            pending.method = "GET";
                            pending.url = ws.group(2);
                            i++;
                            continue;
                        }
                        String[] tokens = splitFirst(raw);
                        if (tokens.length == 2 && METHOD_TOKEN.matcher(tokens[0]).matches()) {
                            pending.method = tokens[0];
                            pending.url = resolveEnv(tokens[1].strip(), lineNo);
                        } else {
                            pending.method = "GET";
                            pending.url = resolveEnv(stripped, lineNo);
                        }
                        applyHttpVersion(pending);
                        applyResponseFileTarget(pending);
                        pending.urlOpen = true;
                        i++;
                        continue;
                    }
                    if (pending.urlOpen) {
                        if (!stripped.isEmpty() && Character.isWhitespace(raw.charAt(0))) {
                            pending.url = pending.url + resolveEnv(stripped, lineNo);
                            applyHttpVersion(pending);
                            applyResponseFileTarget(pending);
                            i++;
                            continue;
                        }
                        pending.urlOpen = false;
                    }
                    if (stripped.startsWith("#") || stripped.startsWith("//")) {
                        Matcher m = NAME_DIRECTIVE.matcher(stripped);
                        if (m.matches()) {
                            pending.name = m.group(1).strip();
                        }
                        i++;
                        continue;
                    }
                    if (stripped.isEmpty()) {
                        state = State.BODY;
                        i++;
                        continue;
                    }
                    if (stripped.startsWith("--")) {
                        state = State.BODY;
                        continue;
                    }
                    Matcher header = HEADER_LINE.matcher(raw);
                    if (header.matches()) {
                        pending.headers.add(new Header(header.group(1).strip(),
                                resolveEnv(header.group(2).strip(), lineNo)));
                        i++;
                        continue;
                    }
                    state = State.BODY;
                    continue;
                }

                if (state == State.BODY) {
                    if (stripped.startsWith("###")) {
                        flushDirectives(pending);
                        finalizeRequest(pending);
                        pending = startRequest(raw, lineNo);
                        pendingName = null;
                        state = State.HEADERS;
                        i++;
                        continue;
                    }
                    if (RESPONSE_FILE_DIRECTIVE.matcher(stripped).matches()) {
                        recordResponseFile(pending, stripped, lineNo);
                        i++;
                        continue;
                    }
                    if (pending.ref == null && pending.bodyLines.isEmpty()) {
                        if (stripped.isEmpty()) {
                            i++;
                            continue;
                        }
                        if (stripped.startsWith("<")) {
                            String rest = stripped.substring(1).strip();
                            if (rest.startsWith("{%")) {
                                preOpener = raw;
                                preCont = new ArrayList<>();
                                if (stripped.endsWith("%}")) {
                                    pending.preScripts.add(new Script(Script.Kind.PREREQUEST,
                                            extractScriptLines(raw, List.of())));
                                    preOpener = null;
                                }
                                i++;
                                continue;
                            }
                            String pathPart = rest.split(";", -1)[0].strip();
                            if (looksLikeRef(pathPart)) {
                                pending.ref = pathPart;
                                i++;
                                continue;
                            }
                        }
                        if (isFileLevelLine(raw, stripped)) {
                            pending.directives.add(raw);
                            i++;
                            continue;
                        }
                        pending.bodyLines.addAll(pending.directives);
                        pending.directives.clear();
                    }
                    if (stripped.startsWith(">")) {
                        flushDirectives(pending);
                        lastRequest = finalizeRequest(pending);
                        pending = null;
                        state = State.AFTER_HANDLER;
                        postOpener = raw;
                        postCont = new ArrayList<>();
                        if (lastRequest == null) {
                            postOpener = null;
                            i++;
                            continue;
                        }
                        if (stripped.endsWith("%}")) {
                            lastRequest.addPostScript(new Script(Script.Kind.TEST,
                                    extractScriptLines(raw, List.of())));
                            postOpener = null;
                        }
                        i++;
                        continue;
                    }
                    pending.bodyLines.add(resolveEnv(raw, lineNo));
                    i++;
                    continue;
                }

                // State.AFTER_HANDLER
                if (stripped.startsWith("###")) {
                    flushPostScript();
                    pending = startRequest(raw, lineNo);
                    pendingName = null;
                    state = State.HEADERS;
                    i++;
                    continue;
                }
                if (RESPONSE_FILE_DIRECTIVE.matcher(stripped).matches()) {
                    reportResponseFile(stripped, lineNo);
                    i++;
                    continue;
                }
                if (stripped.startsWith(">")) {
                    flushPostScript();
                    postOpener = raw;
                    postCont = new ArrayList<>();
                    if (lastRequest != null && stripped.endsWith("%}")) {
                        lastRequest.addPostScript(new Script(Script.Kind.TEST,
                                extractScriptLines(raw, List.of())));
                        postOpener = null;
                    } else if (lastRequest == null) {
                        postOpener = null;
                    }
                    i++;
                    continue;
                }
                if (postOpener != null) {
                    postCont.add(raw);
                    if (stripped.endsWith("%}")) {
                        lastRequest.addPostScript(new Script(Script.Kind.TEST,
                                extractScriptLines(postOpener, postCont)));
                        postOpener = null;
                    }
                    i++;
                    continue;
                }
                if (applyFileLevel(raw, stripped)) {
                    i++;
                    continue;
                }
                Matcher ws = WS_GRPC.matcher(stripped);
                if (ws.matches()) {
                    pending = startRequest(raw, lineNo);
                    pendingName = null;
                    pending.wsGrpcKind = ws.group(1).toUpperCase(Locale.ROOT);
                    pending.wsGrpcUrl = ws.group(2);
                    pending.wsGrpcLine = lineNo;
                    pending.method = "GET";
                    pending.url = ws.group(2);
                    state = State.HEADERS;
                    continue;
                }
                if (looksLikeRequestLine(stripped)) {
                    flushPostScript();
                    pending = startRequest(raw, lineNo);
                    pendingName = null;
                    state = State.HEADERS;
                    continue;
                }
                i++;
            }

            if (preOpener != null && pending != null) {
                pending.preScripts.add(new Script(Script.Kind.PREREQUEST, extractScriptLines(preOpener, preCont)));
            }
            if (postOpener != null && lastRequest != null) {
                lastRequest.addPostScript(new Script(Script.Kind.TEST, extractScriptLines(postOpener, postCont)));
            }
            if (pending != null && pending.method != null) {
                flushDirectives(pending);
                if (!pending.bodyLines.isEmpty()
                        && pending.bodyLines.get(pending.bodyLines.size() - 1).isEmpty()) {
                    pending.bodyLines.remove(pending.bodyLines.size() - 1);
                }
                finalizeRequest(pending);
            }
            return doc;
        }

        // --------------------------------------------------------------
        // file-level helpers
        // --------------------------------------------------------------

        private void openFolder(String name) {
            Folder folder = new Folder(name);
            doc.addItem(folder);
            currentFolder = folder;
        }

        /** Strip a trailing {@code HTTP/2} / {@code HTTP/1.1} pin from the request URL. */
        private void applyHttpVersion(PendingRequest request) {
            if (request.url == null) {
                return;
            }
            Matcher matcher = HTTP_VERSION.matcher(request.url);
            if (!matcher.find()) {
                return;
            }
            request.httpVersion = "2".equals(matcher.group(1))
                    ? (matcher.group(2) != null ? HttpVersion.HTTP_2_PRIOR_KNOWLEDGE : HttpVersion.HTTP_2)
                    : HttpVersion.HTTP_1_1;
            request.url = request.url.substring(0, matcher.start());
        }

        /** Strip a trailing {@code >> file} / {@code >>! file} redirect target from the request URL. */
        private void applyResponseFileTarget(PendingRequest request) {
            if (request.url == null) {
                return;
            }
            Matcher matcher = INLINE_RESPONSE_FILE.matcher(request.url);
            if (!matcher.find()) {
                return;
            }
            request.responseFile = matcher.group().strip();
            request.responseFileLine = request.line;
            request.url = request.url.substring(0, matcher.start());
        }

        /** Remember a standalone {@code >> file} redirect: Postman cannot save responses to disk. */
        private void recordResponseFile(PendingRequest request, String directive, int lineNo) {
            if (request == null) {
                return;
            }
            request.responseFile = directive.strip();
            request.responseFileLine = lineNo;
        }

        private void reportResponseFile(String directive, int lineNo) {
            doc.addWarning(new UnsupportedItem(
                    UnsupportedItem.Kind.RESPONSE_FILE,
                    "response redirect '" + directive.strip() + "' has no Postman equivalent",
                    source,
                    lineNo));
        }

        /** Substitute {@code {{$dotenv KEY}}} tokens, reporting keys the env file does not define. */
        private String resolveEnv(String text, int line) {
            Matcher matcher = DOTENV_TOKEN.matcher(text);
            StringBuilder out = new StringBuilder(text.length());
            while (matcher.find()) {
                String key = matcher.group(1);
                if (envVars.containsKey(key)) {
                    matcher.appendReplacement(out, Matcher.quoteReplacement(envVars.get(key)));
                } else {
                    doc.addWarning(new UnsupportedItem(UnsupportedItem.Kind.DOTENV,
                            "unresolved dotenv reference '" + matcher.group(0) + "'", source, line));
                    matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                }
            }
            matcher.appendTail(out);
            return out.toString();
        }

        private void applyVariable(String raw) {
            int eq = raw.indexOf('=');
            String key = raw.substring(0, eq).strip();
            int start = 0;
            while (start < key.length() && key.charAt(start) == '@') {
                start++;
            }
            key = key.substring(start).strip();
            doc.putVariable(key, raw.substring(eq + 1).strip());
        }

        /** Handle a line as file-level content; false when the caller must handle it. */
        private boolean applyFileLevel(String raw, String stripped) {
            if (stripped.startsWith("###")) {
                return false;
            }
            if (stripped.startsWith("##")) {
                String name = stripped.substring(2).strip();
                if (!name.isEmpty()) {
                    openFolder(name);
                }
                return true;
            }
            if (stripped.startsWith("#")) {
                Matcher m = NAME_DIRECTIVE.matcher(stripped);
                if (m.matches()) {
                    pendingName = m.group(1).strip();
                }
                return true;
            }
            if (stripped.startsWith("//")) {
                return true;
            }
            if (raw.contains("=") && stripped.startsWith("@")) {
                applyVariable(raw);
                return true;
            }
            if (stripped.startsWith("@group")) {
                String name = stripped.substring("@group".length()).strip();
                if (!name.isEmpty()) {
                    openFolder(name);
                }
                return true;
            }
            return stripped.isEmpty();
        }

        private boolean isFileLevelLine(String raw, String stripped) {
            if (stripped.isEmpty()) {
                return true;
            }
            if (stripped.startsWith("##")) {
                return true;
            }
            if (stripped.startsWith("#")) {
                return true;
            }
            if (stripped.startsWith("//")) {
                return true;
            }
            return stripped.startsWith("@") && (raw.contains("=") || stripped.startsWith("@group"));
        }

        private void flushDirectives(PendingRequest request) {
            for (String directive : request.directives) {
                applyFileLevel(directive, directive.strip());
            }
            request.directives.clear();
        }

        private void flushPostScript() {
            if (postOpener != null && lastRequest != null) {
                lastRequest.addPostScript(new Script(Script.Kind.TEST, extractScriptLines(postOpener, postCont)));
            }
            postOpener = null;
            postCont = new ArrayList<>();
        }

        private PendingRequest startRequest(String line, int lineNo) {
            PendingRequest request = new PendingRequest(lineNo, currentFolder);
            String stripped = line.strip();
            String name = "";
            if (stripped.startsWith("###")) {
                name = stripped.substring(3).strip();
            }
            if (name.isEmpty() && pendingName != null) {
                name = pendingName;
            }
            request.name = name;
            return request;
        }

        // --------------------------------------------------------------
        // script helpers
        // --------------------------------------------------------------

        private static boolean isPreRequestStart(String stripped) {
            return stripped.startsWith("<") && stripped.substring(1).strip().startsWith("{%");
        }

        private static List<String> extractScriptLines(String firstLine, List<String> continuation) {
            String content = firstLine.strip();
            if (!content.isEmpty() && (content.charAt(0) == '>' || content.charAt(0) == '<')) {
                content = content.substring(1).strip();
            }
            if (content.startsWith("{%")) {
                content = content.substring(2).strip();
            }
            List<String> lines = new ArrayList<>();
            lines.add(content);
            lines.addAll(continuation);
            String last = lines.get(lines.size() - 1);
            int close = last.lastIndexOf("%}");
            if (close >= 0) {
                lines.set(lines.size() - 1, last.substring(0, close).stripTrailing());
            }
            while (!lines.isEmpty() && lines.get(0).isEmpty()) {
                lines.remove(0);
            }
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            return lines;
        }

        private static boolean looksLikeRequestLine(String stripped) {
            for (String prefix : URL_PREFIXES) {
                if (stripped.startsWith(prefix)) {
                    return true;
                }
            }
            String[] tokens = splitFirst(stripped);
            return tokens.length == 2 && KNOWN_METHODS.contains(tokens[0].toUpperCase(Locale.ROOT));
        }

        // --------------------------------------------------------------
        // request finalisation
        // --------------------------------------------------------------

        private Request finalizeRequest(PendingRequest request) {
            if (request == null || request.method == null) {
                return null;
            }
            if (request.wsGrpcKind != null) {
                doc.addWarning(new UnsupportedItem(
                        "WEBSOCKET".equals(request.wsGrpcKind)
                                ? UnsupportedItem.Kind.WEBSOCKET_REQUEST
                                : UnsupportedItem.Kind.GRPC_REQUEST,
                        request.wsGrpcUrl,
                        source,
                        request.wsGrpcLine));
                return null;
            }
            String contentType = headerValue(request.headers, "content-type");
            if (request.responseFile != null) {
                reportResponseFile(request.responseFile, request.responseFileLine);
            }
            Body body = buildBody(request, contentType);
            Request built = new Request(request.name, request.method, request.url, request.headers, body,
                    request.httpVersion, source, request.line);
            for (Script script : request.preScripts) {
                built.addPreScript(script);
            }
            if (request.folder != null) {
                request.folder.add(built);
            } else {
                doc.addItem(built);
            }
            return built;
        }

        private Body buildBody(PendingRequest request, String contentType) {
            List<String> bodyLines = request.bodyLines;
            while (!bodyLines.isEmpty() && bodyLines.get(bodyLines.size() - 1).isEmpty()) {
                bodyLines.remove(bodyLines.size() - 1);
            }
            String bodyText = String.join("\n", bodyLines);

            if (request.ref != null) {
                return buildRefBody(request.ref, contentType, request.line);
            }
            if (isGraphql(request.headers)) {
                return rawBody(bodyText, contentType, request.headers);
            }
            String lowered = contentType == null ? null : contentType.toLowerCase(Locale.ROOT);
            if (lowered != null && lowered.startsWith("multipart")) {
                String boundary = extractBoundary(contentType);
                if (boundary == null) {
                    doc.addWarning(new UnsupportedItem(
                            UnsupportedItem.Kind.MULTIPART_NO_BOUNDARY,
                            "multipart Content-Type without boundary parameter; body treated as text",
                            source,
                            request.line));
                    return rawBody(bodyText, contentType, request.headers);
                }
                MultipartResult result = parseMultipartParts(bodyLines, boundary);
                if (!result.closed()) {
                    doc.addWarning(new UnsupportedItem(
                            UnsupportedItem.Kind.MULTIPART_UNTERMINATED,
                            "multipart body missing closing boundary",
                            source,
                            request.line));
                }
                return Body.multipart(result.parts());
            }
            if (lowered != null && lowered.equals("application/x-www-form-urlencoded")) {
                return Body.formUrlencoded(parseForm(bodyText));
            }
            if (!hasContent(bodyLines)) {
                return null;
            }
            if (contentType == null) {
                String sniffed = sniffContentType(bodyText);
                request.headers.add(new Header("Content-Type", sniffed));
                if (sniffed.equals("application/x-www-form-urlencoded")) {
                    return Body.formUrlencoded(parseForm(bodyText));
                }
                return Body.raw(bodyText);
            }
            return Body.raw(bodyText);
        }

        private static Body rawBody(String bodyText, String contentType, List<Header> headers) {
            if (contentType == null && hasContent(List.of(bodyText))) {
                headers.add(new Header("Content-Type", sniffContentType(bodyText)));
            }
            return bodyText.isEmpty() ? null : Body.raw(bodyText);
        }

        private Body buildRefBody(String ref, String contentType, int line) {
            Path sourceDir = Path.of(source).toAbsolutePath().getParent();
            if (sourceDir == null) {
                sourceDir = Path.of(".");
            }
            Path refPath = sourceDir.resolve(ref);
            if (contentType != null && !isTextContentType(contentType)) {
                return Body.file(ref);
            }
            try {
                return Body.raw(decodeStrict(Files.readAllBytes(refPath)));
            } catch (IOException e) {
                doc.addWarning(new UnsupportedItem(
                        UnsupportedItem.Kind.EXTERNAL_REF_UNREADABLE,
                        "could not read external reference '" + ref + "'",
                        source,
                        line));
                return Body.raw(String.format(PLACEHOLDER_REF, ref));
            }
        }

        private MultipartResult parseMultipartParts(List<String> bodyLines, String boundary) {
            List<BodyPart> parts = new ArrayList<>();
            boolean closed = false;
            int i = 0;
            int n = bodyLines.size();
            String boundaryLine = "--" + boundary;
            String closingLine = "--" + boundary + "--";
            while (i < n) {
                String stripped = bodyLines.get(i).strip();
                if (stripped.equals(closingLine)) {
                    closed = true;
                    break;
                }
                if (!stripped.equals(boundaryLine)) {
                    i++;
                    continue;
                }
                i++;
                Map<String, String> partHeaders = new LinkedHashMap<>();
                while (i < n && !bodyLines.get(i).strip().isEmpty()) {
                    Matcher m = HEADER_LINE.matcher(bodyLines.get(i).strip());
                    if (m.matches()) {
                        partHeaders.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2).strip());
                    }
                    i++;
                }
                if (i < n && bodyLines.get(i).strip().isEmpty()) {
                    i++;
                }
                List<String> valueLines = new ArrayList<>();
                while (i < n) {
                    String value = bodyLines.get(i).strip();
                    if (value.equals(boundaryLine) || value.equals(closingLine)) {
                        break;
                    }
                    valueLines.add(bodyLines.get(i));
                    i++;
                }
                String disposition = partHeaders.getOrDefault("content-disposition", "");
                Matcher nameMatcher = CD_NAME.matcher(disposition);
                Matcher filenameMatcher = CD_FILENAME.matcher(disposition);
                String partName = nameMatcher.find() ? nameMatcher.group(1) : "";
                if (filenameMatcher.find()) {
                    String filename = filenameMatcher.group(1);
                    ensurePlaceholderFile(filename);
                    parts.add(BodyPart.file(partName, filename));
                } else {
                    parts.add(BodyPart.text(partName, String.join("\n", valueLines)));
                }
            }
            return new MultipartResult(parts, closed);
        }

        /**
         * Create an EMPTY placeholder file for a multipart file field.
         *
         * <p>Never overwrites an existing file and never creates anything outside
         * the output directory (path-traversal guard).
         */
        private void ensurePlaceholderFile(String filename) {
            Path base = outputDir.toAbsolutePath().normalize();
            Path target = base.resolve(filename).normalize();
            if (!target.startsWith(base)) {
                return;
            }
            if (Files.exists(target)) {
                return;
            }
            try {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.createFile(target);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    // ------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------

    private static String headerValue(List<Header> headers, String key) {
        for (Header header : headers) {
            if (header.key().equalsIgnoreCase(key)) {
                return header.value();
            }
        }
        return null;
    }

    private static boolean isGraphql(List<Header> headers) {
        String value = headerValue(headers, "x-request-type");
        return value != null && value.strip().equalsIgnoreCase("graphql");
    }

    private static String extractBoundary(String contentType) {
        Matcher m = BOUNDARY_PARAM.matcher(contentType);
        return m.find() ? m.group(1) : null;
    }

    private static boolean hasContent(List<String> bodyLines) {
        for (String line : bodyLines) {
            if (!line.strip().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Sniff a Content-Type from the body text when the request omits it. */
    static String sniffContentType(String content) {
        String c = content.strip();
        if (c.startsWith("{") || c.startsWith("[")) {
            return "application/json";
        }
        if (c.startsWith("<")) {
            return "application/xml";
        }
        if (!c.contains("\n") && c.contains("=") && FORM.matcher(c).matches()) {
            return "application/x-www-form-urlencoded";
        }
        return "text/plain";
    }

    private static boolean isTextContentType(String contentType) {
        String ct = contentType.toLowerCase(Locale.ROOT);
        for (String prefix : TEXT_CONTENT_TYPES) {
            if (ct.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<FormField> parseForm(String content) {
        List<FormField> pairs = new ArrayList<>();
        for (String pair : content.split("&", -1)) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                pairs.add(new FormField(unquotePlus(pair.substring(0, eq).strip()),
                        unquotePlus(pair.substring(eq + 1).strip())));
            } else {
                pairs.add(new FormField(unquotePlus(pair.strip()), ""));
            }
        }
        return pairs;
    }

    private static boolean looksLikeRef(String rest) {
        if (rest.isEmpty() || rest.contains(">") || rest.contains(" ")) {
            return false;
        }
        for (String prefix : REF_PREFIXES) {
            if (rest.startsWith(prefix)) {
                return true;
            }
        }
        return rest.contains(".") || rest.contains("{");
    }

    /** Equivalent of Python's {@code str.split(None, 1)}. */
    static String[] splitFirst(String text) {
        int n = text.length();
        int i = 0;
        while (i < n && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= n) {
            return new String[0];
        }
        int start = i;
        while (i < n && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        String first = text.substring(start, i);
        while (i < n && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= n) {
            return new String[]{first};
        }
        return new String[]{first, text.substring(i)};
    }

    /** Equivalent of Python's {@code urllib.parse.unquote_plus}. */
    static String unquotePlus(String text) {
        return PercentCodec.decodePlus(text);
    }
}
