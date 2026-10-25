/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.converter;

import com.auhouhs.http2postman.model.Body;
import com.auhouhs.http2postman.model.BodyPart;
import com.auhouhs.http2postman.model.DocItem;
import com.auhouhs.http2postman.model.Folder;
import com.auhouhs.http2postman.model.FormField;
import com.auhouhs.http2postman.model.Header;
import com.auhouhs.http2postman.model.Request;
import com.auhouhs.http2postman.model.Script;
import com.auhouhs.http2postman.model.SourceDoc;
import com.auhouhs.http2postman.model.UnsupportedItem;
import com.auhouhs.http2postman.postman.PostmanAuth;
import com.auhouhs.http2postman.postman.PostmanBody;
import com.auhouhs.http2postman.postman.PostmanCollection;
import com.auhouhs.http2postman.postman.PostmanEvent;
import com.auhouhs.http2postman.postman.PostmanFile;
import com.auhouhs.http2postman.postman.PostmanFormData;
import com.auhouhs.http2postman.postman.PostmanInfo;
import com.auhouhs.http2postman.postman.PostmanItem;
import com.auhouhs.http2postman.postman.PostmanJson;
import com.auhouhs.http2postman.postman.PostmanKeyValue;
import com.auhouhs.http2postman.postman.PostmanRawOptions;
import com.auhouhs.http2postman.postman.PostmanRequest;
import com.auhouhs.http2postman.postman.PostmanScript;
import com.auhouhs.http2postman.postman.PostmanUrl;
import com.auhouhs.http2postman.scripts.ScriptTranslator;
import com.auhouhs.http2postman.util.Names;
import com.auhouhs.http2postman.util.PercentCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Convert parsed {@link SourceDoc}s into a Postman Collection v2.1 document. */
public final class PostmanConverter {

    /** REQ-PAR-007 dynamic variable mapping (JetBrains -&gt; Postman). */
    private record DynamicRule(Pattern pattern, String replacement) {
    }

    private static final List<DynamicRule> DYNAMIC_VARIABLES = List.of(
            rule("\\$random\\.integer\\s*\\([^)]*\\)", "{{$randomInt}}"),
            rule("\\$random\\.uuid\\b", "{{$randomUUID}}"),
            rule("\\$uuid\\b", "{{$guid}}"),
            rule("\\$timestamp\\b", "{{$timestamp}}"),
            rule("\\$randomInt\\b", "{{$randomInt}}"),
            rule("\\$isoTimestamp\\b", "{{$isoTimestamp}}"));

    private static final Pattern DOTENV_RE = Pattern.compile("\\{\\{\\s*\\$dotenv\\s+[^}]*\\}\\}");
    private static final Pattern UNMAPPED_TOKEN_RE = Pattern.compile("(?<!\\{)\\$[A-Za-z][A-Za-z0-9_.]*");

    // Verified against https://learning.postman.com/docs/tests-and-scripts/write-scripts/variables-list/
    // (exact, case-sensitive Postman dynamic-variable names).
    private static final Set<String> POSTMAN_RANDOM_ALLOWLIST = Set.of(
            "guid", "timestamp", "isoTimestamp", "randomUUID",
            "randomAlphaNumeric", "randomBoolean", "randomInt", "randomColor", "randomHexColor",
            "randomAbbreviation", "randomIP", "randomIPV6", "randomMACAddress", "randomPassword",
            "randomLocale", "randomUserAgent", "randomProtocol", "randomSemver",
            "randomFirstName", "randomLastName", "randomFullName", "randomNamePrefix", "randomNameSuffix",
            "randomJobArea", "randomJobDescriptor", "randomJobTitle", "randomJobType",
            "randomPhoneNumber", "randomPhoneNumberExt", "randomCity", "randomStreetName",
            "randomStreetAddress", "randomCountry", "randomCountryCode", "randomLatitude", "randomLongitude",
            "randomAvatarImage", "randomImageUrl", "randomAbstractImage", "randomAnimalsImage",
            "randomBusinessImage", "randomCatsImage", "randomCityImage", "randomFoodImage",
            "randomNightlifeImage", "randomFashionImage", "randomPeopleImage", "randomNatureImage",
            "randomSportsImage", "randomTransportImage", "randomImageDataUri",
            "randomBankAccount", "randomBankAccountName", "randomCreditCardMask", "randomBankAccountBic",
            "randomBankAccountIban", "randomTransactionType", "randomCurrencyCode", "randomCurrencyName",
            "randomCurrencySymbol", "randomBitcoin",
            "randomCompanyName", "randomCompanySuffix", "randomBs", "randomBsAdjective",
            "randomBsBuzz", "randomBsNoun",
            "randomCatchPhrase", "randomCatchPhraseAdjective", "randomCatchPhraseDescriptor",
            "randomCatchPhraseNoun",
            "randomDatabaseColumn", "randomDatabaseType", "randomDatabaseCollation", "randomDatabaseEngine",
            "randomDateFuture", "randomDatePast", "randomDateRecent", "randomWeekday", "randomMonth",
            "randomDomainName", "randomDomainSuffix", "randomDomainWord", "randomEmail", "randomExampleEmail",
            "randomUserName", "randomUrl",
            "randomFileName", "randomFileType", "randomFileExt", "randomCommonFileName",
            "randomCommonFileType", "randomCommonFileExt", "randomFilePath", "randomDirectoryPath",
            "randomMimeType",
            "randomPrice", "randomProduct", "randomProductAdjective", "randomProductMaterial",
            "randomProductName", "randomDepartment",
            "randomNoun", "randomVerb", "randomIngverb", "randomAdjective", "randomWord", "randomWords",
            "randomPhrase",
            "randomLoremWord", "randomLoremWords", "randomLoremSentence", "randomLoremSentences",
            "randomLoremParagraph", "randomLoremParagraphs", "randomLoremText", "randomLoremSlug",
            "randomLoremLines");

    private static final Map<String, String> RANDOM_OVERRIDES = Map.ofEntries(
            Map.entry("alphanumeric", "randomAlphaNumeric"),
            Map.entry("bool", "randomBoolean"),
            Map.entry("word", "randomWord"),
            Map.entry("firstname", "randomFirstName"),
            Map.entry("lastname", "randomLastName"),
            Map.entry("email", "randomEmail"),
            Map.entry("uuid", "randomUUID"),
            Map.entry("ipv6", "randomIPV6"),
            Map.entry("macAddress", "randomMACAddress"),
            Map.entry("username", "randomUserName"),
            Map.entry("company", "randomCompanyName"),
            Map.entry("phone", "randomPhoneNumber"));

    private static final Pattern RANDOM_CONVENTION_RE = Pattern.compile("\\$random\\.([A-Za-z][A-Za-z0-9_.]*)");
    private static final Pattern SCHEME_RE = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*|\\{\\{[^}]+}})://");

    private PostmanConverter() {
    }

    private static DynamicRule rule(String pattern, String replacement) {
        return new DynamicRule(Pattern.compile(pattern), replacement);
    }

    public static PostmanCollection convert(List<SourceDoc> sourceDocs, String collectionName) {
        return convert(sourceDocs, collectionName, null, new ArrayList<>(), null);
    }

    /**
     * Build a Postman Collection v2.1 document from one or more source docs.
     *
     * @param sourcePaths  source file names, used for folder names and the description
     * @param warnings     collector for anything that could not be expressed faithfully
     * @param envDefaults  collection-variable defaults (e.g. from
     *                     {@code http-client.env.json}); they win over file-level
     *                     {@code @var} definitions with the same key
     */
    public static PostmanCollection convert(List<SourceDoc> sourceDocs, String collectionName,
                                            List<String> sourcePaths, List<UnsupportedItem> warnings,
                                            Map<String, String> envDefaults) {
        List<String> sourceFiles = sourceFiles(sourceDocs, sourcePaths);
        PostmanInfo info = new PostmanInfo(collectionName, "Generated from: " + String.join(", ", sourceFiles),
                PostmanJson.SCHEMA_URL);

        Map<String, String> variables = new LinkedHashMap<>();
        for (SourceDoc doc : sourceDocs) {
            variables.putAll(doc.variables());
        }
        if (envDefaults != null) {
            variables.putAll(envDefaults);
        }

        Map<String, Integer> usedNames = new HashMap<>();
        List<PostmanItem> collectionItems;
        if (sourceDocs.size() == 1) {
            collectionItems = convertItems(sourceDocs.get(0).items(), warnings, usedNames);
        } else {
            collectionItems = new ArrayList<>();
            for (int i = 0; i < sourceDocs.size(); i++) {
                String folderName = i < sourceFiles.size() ? folderName(sourceFiles.get(i)) : "file-" + (i + 1);
                collectionItems.add(PostmanItem.folder(folderName,
                        convertItems(sourceDocs.get(i).items(), warnings, usedNames)));
            }
        }

        List<PostmanKeyValue> collectionVariables = new ArrayList<>();
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            if (variable.getValue() != null && !variable.getValue().isEmpty()) {
                collectionVariables.add(new PostmanKeyValue(variable.getKey(), variable.getValue()));
            }
        }
        return new PostmanCollection(info, collectionItems, collectionVariables);
    }

    private static List<String> sourceFiles(List<SourceDoc> sourceDocs, List<String> sourcePaths) {
        if (sourcePaths != null) {
            return new ArrayList<>(sourcePaths);
        }
        List<String> files = new ArrayList<>();
        for (SourceDoc doc : sourceDocs) {
            String file = "<string>";
            for (DocItem item : doc.items()) {
                if (item instanceof Request request) {
                    file = request.sourceFile();
                    break;
                }
            }
            files.add(file);
        }
        return files;
    }

    private static String folderName(String path) {
        return Names.titleCase(Names.stripExtension(Names.baseName(path)));
    }

    private static List<PostmanItem> convertItems(List<DocItem> items, List<UnsupportedItem> warnings,
                                                  Map<String, Integer> usedNames) {
        List<PostmanItem> out = new ArrayList<>();
        for (DocItem item : items) {
            if (item instanceof Folder folder) {
                out.add(PostmanItem.folder(folder.name(), convertItems(folder.items(), warnings, usedNames)));
            } else if (item instanceof Request request) {
                out.add(convertRequest(request, warnings, usedNames));
            }
        }
        return out;
    }

    private static PostmanItem convertRequest(Request request, List<UnsupportedItem> warnings,
                                              Map<String, Integer> usedNames) {
        String rawUrl = request.url();
        String mappedUrl = mapDynamicVariables(rawUrl, request, warnings);
        PostmanUrl url = buildUrl(mappedUrl);

        String name = request.name().isEmpty() ? request.method() + " " + rawUrl : request.name();
        name = uniquify(name, usedNames);

        List<PostmanKeyValue> headers = convertHeaders(request.headers(), request, warnings);
        PostmanAuth auth = AuthDetector.authForRequest(request, mappedUrl);
        if (auth != null) {
            headers = headers.stream()
                    .filter(header -> !header.key().equalsIgnoreCase("authorization"))
                    .toList();
        }
        PostmanBody body = request.body() != null ? convertBody(request, warnings) : null;
        List<PostmanEvent> events = convertScripts(request);

        return PostmanItem.request(name, new PostmanRequest(request.method(), headers, url, body), auth,
                events.isEmpty() ? null : events);
    }

    private static List<PostmanEvent> convertScripts(Request request) {
        List<PostmanEvent> events = new ArrayList<>();
        if (!request.preScripts().isEmpty()) {
            List<String> exec = new ArrayList<>();
            for (Script script : request.preScripts()) {
                exec.addAll(ScriptTranslator.translate(script.kind(), script.lines()));
            }
            events.add(PostmanEvent.preRequest(PostmanScript.javascript(exec)));
        }
        if (!request.postScripts().isEmpty()) {
            List<String> exec = new ArrayList<>();
            for (Script script : request.postScripts()) {
                exec.addAll(ScriptTranslator.translate(script.kind(), script.lines()));
            }
            events.add(PostmanEvent.test(PostmanScript.javascript(exec)));
        }
        return events;
    }

    private static String uniquify(String name, Map<String, Integer> usedNames) {
        if (!usedNames.containsKey(name)) {
            usedNames.put(name, 1);
            return name;
        }
        int n = 2;
        String candidate = name + " (" + n + ")";
        while (usedNames.containsKey(candidate)) {
            n++;
            candidate = name + " (" + n + ")";
        }
        usedNames.put(candidate, 1);
        return candidate;
    }

    private static List<PostmanKeyValue> convertHeaders(List<Header> headers, Request request,
                                                        List<UnsupportedItem> warnings) {
        List<PostmanKeyValue> out = new ArrayList<>();
        for (Header header : headers) {
            if (header.key().equalsIgnoreCase("content-length")) {
                continue;
            }
            out.add(new PostmanKeyValue(header.key(),
                    mapDynamicVariables(header.value(), request, warnings)));
        }
        return out;
    }

    private static String headerValue(List<Header> headers, String key) {
        for (Header header : headers) {
            if (header.key().equalsIgnoreCase(key)) {
                return header.value();
            }
        }
        return null;
    }

    private static PostmanBody convertBody(Request request, List<UnsupportedItem> warnings) {
        Body body = request.body();
        return switch (body.kind()) {
            case RAW -> new PostmanBody("raw",
                    mapDynamicVariables(body.content() == null ? "" : body.content(), request, warnings),
                    PostmanRawOptions.language(rawLanguage(request)),
                    null, null, null);
            case FORM_URLENCODED -> new PostmanBody("urlencoded", null, null,
                    body.form().stream()
                            .map(field -> new PostmanKeyValue(
                                    mapDynamicVariables(field.name(), request, warnings),
                                    mapDynamicVariables(field.value(), request, warnings)))
                            .toList(),
                    null, null);
            case MULTIPART -> new PostmanBody("formdata", null, null, null,
                    convertFormdata(body.parts(), request, warnings), null);
            case FILE -> new PostmanBody("file", null, null, null, null,
                    new PostmanFile(body.fileSrc() == null ? "" : body.fileSrc()));
        };
    }

    private static List<PostmanFormData> convertFormdata(List<BodyPart> parts, Request request,
                                                         List<UnsupportedItem> warnings) {
        List<PostmanFormData> out = new ArrayList<>();
        for (BodyPart part : parts) {
            if (part.isFile()) {
                out.add(PostmanFormData.file(part.name(), part.filename()));
            } else {
                out.add(PostmanFormData.text(part.name(),
                        mapDynamicVariables(part.value() == null ? "" : part.value(), request, warnings)));
            }
        }
        return out;
    }

    private static String rawLanguage(Request request) {
        String contentType = headerValue(request.headers(), "content-type");
        String lowered = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lowered.contains("json")) {
            return "json";
        }
        if (lowered.contains("xml")) {
            return "xml";
        }
        if (lowered.contains("html")) {
            return "html";
        }
        String content = request.body() != null && request.body().content() != null
                ? request.body().content()
                : "";
        String trimmed = content.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return "json";
        }
        return "text";
    }

    private static PostmanUrl buildUrl(String raw) {
        String rest = raw;
        String protocol = null;

        Matcher scheme = SCHEME_RE.matcher(raw);
        if (scheme.find()) {
            protocol = scheme.group(1);
            rest = raw.substring(scheme.end());
        }

        int hash = rest.indexOf('#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }

        List<PostmanKeyValue> query = null;
        int question = rest.indexOf('?');
        if (question >= 0) {
            query = parseQuery(rest.substring(question + 1));
            rest = rest.substring(0, question);
        }

        String authority = rest;
        String path = "";
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            authority = rest.substring(0, slash);
            path = rest.substring(slash);
        }

        String hostPort = authority;
        String port = null;
        if (!authority.isEmpty()) {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                String candidatePort = authority.substring(colon + 1);
                if (!candidatePort.isEmpty()) {
                    port = candidatePort;
                }
                hostPort = authority.substring(0, colon);
            }
        }
        List<String> host = hostPort.isEmpty() ? null : splitHost(hostPort);

        List<String> pathSegments = null;
        if (!path.isEmpty()) {
            List<String> segments = new ArrayList<>(Arrays.asList(path.split("/", -1)));
            if (!segments.isEmpty() && segments.get(0).isEmpty()) {
                segments.remove(0);
            }
            if (!segments.isEmpty()) {
                pathSegments = segments;
            }
        }

        return new PostmanUrl(raw, protocol, host, port, pathSegments, query);
    }

    /** Split on '.' outside {@code {{...}}} token spans so dotted tokens survive. */
    private static List<String> splitHost(String host) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int braceDepth = 0;
        for (int i = 0; i < host.length(); i++) {
            char ch = host.charAt(i);
            if (ch == '{') {
                braceDepth++;
            } else if (ch == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
            }
            if (ch == '.' && braceDepth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static List<PostmanKeyValue> parseQuery(String queryPart) {
        List<PostmanKeyValue> pairs = new ArrayList<>();
        for (String pair : queryPart.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                pairs.add(new PostmanKeyValue(decodeQueryComponent(pair.substring(0, eq)),
                        decodeQueryComponent(pair.substring(eq + 1))));
            } else {
                pairs.add(new PostmanKeyValue(decodeQueryComponent(pair), ""));
            }
        }
        return pairs;
    }

    /**
     * Encoded braces must not be decoded into live variable markers, and
     * {@code +} is literal in URLs, so no plus-to-space conversion happens here.
     */
    private static String decodeQueryComponent(String component) {
        if (component.contains("{{") || component.toLowerCase(Locale.ROOT).contains("%7b")) {
            return component;
        }
        return PercentCodec.percentDecode(component);
    }

    private static String mapDynamicVariables(String text, Request request, List<UnsupportedItem> warnings) {
        for (DynamicRule rule : DYNAMIC_VARIABLES) {
            text = applyBraceAware(rule, text);
        }
        text = applyRandomConvention(text, request, warnings);

        String scanText = DOTENV_RE.matcher(text).replaceAll("");
        Matcher unmapped = UNMAPPED_TOKEN_RE.matcher(scanText);
        while (unmapped.find()) {
            String token = unmapped.group();
            if (token.startsWith("$random")) {
                continue;
            }
            if (token.equals("$uuid") || token.equals("$timestamp")
                    || token.equals("$randomInt") || token.equals("$randomUUID")) {
                continue;
            }
            warnings.add(new UnsupportedItem(
                    UnsupportedItem.Kind.DYNAMIC_VARIABLE,
                    "unmapped dynamic variable '" + token + "' left as-is",
                    request.sourceFile(),
                    request.sourceLine()));
        }
        return text;
    }

    /**
     * Table replacements wrap tokens in {@code {{ }}}; when the source token is
     * already inside braces ({@code {{$uuid}}}), emit the bare {@code $name}
     * instead of double-wrapping.
     */
    private static String applyBraceAware(DynamicRule rule, String text) {
        Matcher matcher = rule.pattern().matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            boolean alreadyBraced = matcher.start() >= 2 && text.startsWith("{{", matcher.start() - 2);
            String replacement = rule.replacement();
            if (replacement.startsWith("{{") && alreadyBraced) {
                replacement = replacement.substring(2, replacement.length() - 2);
            }
            out.append(replacement);
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    private static String applyRandomConvention(String text, Request request, List<UnsupportedItem> warnings) {
        Matcher matcher = RANDOM_CONVENTION_RE.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            String first = matcher.group(1).split("\\.", -1)[0];
            String name = RANDOM_OVERRIDES.getOrDefault(first,
                    "random" + Character.toUpperCase(first.charAt(0)) + first.substring(1));
            if (POSTMAN_RANDOM_ALLOWLIST.contains(name)) {
                boolean alreadyBraced = matcher.start() >= 2 && text.startsWith("{{", matcher.start() - 2);
                out.append(alreadyBraced ? "$" + name : "{{$" + name + "}}");
            } else {
                warnings.add(new UnsupportedItem(
                        UnsupportedItem.Kind.DYNAMIC_VARIABLE,
                        "unmapped dynamic variable '$random." + matcher.group(1) + "' left as-is",
                        request.sourceFile(),
                        request.sourceLine()));
                out.append(matcher.group());
            }
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }
}
