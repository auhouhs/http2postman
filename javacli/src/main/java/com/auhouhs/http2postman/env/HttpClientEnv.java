/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.env;

import com.auhouhs.http2postman.postman.PostmanJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Reader for JetBrains {@code http-client.env.json} / {@code http-client.private.env.json}. */
public final class HttpClientEnv {

    public static final String ENV_FILE = "http-client.env.json";
    public static final String PRIVATE_ENV_FILE = "http-client.private.env.json";

    private HttpClientEnv() {
    }

    /**
     * Load the named environment's variables from {@code http-client.env.json}
     * and {@code http-client.private.env.json} in {@code directory} (private
     * merged on top).
     *
     * <p>Values are coerced to strings; complex values become compact JSON.
     *
     * @throws EnvFileException when no env file exists or the environment name is absent
     */
    public static Map<String, String> load(Path directory, String envName) throws EnvFileException {
        Map<String, Map<String, JsonNode>> merged = new LinkedHashMap<>();
        boolean foundAny = false;
        for (String filename : List.of(ENV_FILE, PRIVATE_ENV_FILE)) {
            Path path = directory.resolve(filename);
            JsonNode envs = readEnvFile(path);
            if (envs == null) {
                continue;
            }
            foundAny = true;
            Iterator<Map.Entry<String, JsonNode>> fields = envs.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!entry.getValue().isObject()) {
                    continue;
                }
                Map<String, JsonNode> target = merged.computeIfAbsent(entry.getKey(), key -> new LinkedHashMap<>());
                Iterator<Map.Entry<String, JsonNode>> variables = entry.getValue().fields();
                while (variables.hasNext()) {
                    Map.Entry<String, JsonNode> variable = variables.next();
                    target.put(variable.getKey(), variable.getValue());
                }
            }
        }
        if (!foundAny) {
            throw new EnvFileException(ENV_FILE + " not found next to the input (" + directory + "); "
                    + "--prefer-env requires a JetBrains HTTP Client environment file");
        }
        Map<String, JsonNode> selected = merged.get(envName);
        if (selected == null) {
            throw new EnvFileException("environment '" + envName + "' not found in " + ENV_FILE
                    + " (available: " + availableNames(merged) + ")");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : selected.entrySet()) {
            result.put(entry.getKey(), coerceValue(entry.getValue()));
        }
        return result;
    }

    /**
     * Environment names declared in {@code http-client.env.json} and
     * {@code http-client.private.env.json} in {@code directory}, sorted.
     *
     * @throws EnvFileException when an existing env file cannot be parsed
     */
    public static List<String> names(Path directory) throws EnvFileException {
        Set<String> names = new TreeSet<>();
        for (String filename : List.of(ENV_FILE, PRIVATE_ENV_FILE)) {
            JsonNode envs = readEnvFile(directory.resolve(filename));
            if (envs == null) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = envs.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getValue().isObject()) {
                    names.add(entry.getKey());
                }
            }
        }
        return new ArrayList<>(names);
    }

    private static String availableNames(Map<String, Map<String, JsonNode>> merged) {
        if (merged.isEmpty()) {
            return "none";
        }
        return String.join(", ", new TreeSet<>(merged.keySet()));
    }

    /** Parse an env JSON file; {@code null} when missing, error when invalid. */
    private static JsonNode readEnvFile(Path path) throws EnvFileException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            return null;
        }
        JsonNode parsed;
        try {
            String text = new String(data, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            parsed = PostmanJson.mapper().readTree(text);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new EnvFileException("cannot parse " + path + ": " + e.getMessage(), e);
        }
        if (parsed == null || !parsed.isObject()) {
            throw new EnvFileException(path + " must be a JSON object of environments");
        }
        return parsed;
    }

    private static String coerceValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            // Python renders bools as True/False.
            return value.booleanValue() ? "True" : "False";
        }
        if (value.isNumber()) {
            return value.asText();
        }
        try {
            return PostmanJson.mapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return value.toString();
        }
    }
}
