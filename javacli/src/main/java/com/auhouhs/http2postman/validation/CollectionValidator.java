/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.validation;

import com.auhouhs.http2postman.postman.PostmanCollection;
import com.auhouhs.http2postman.postman.PostmanJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Structural validation of a Postman collection. Pure findings only. */
public final class CollectionValidator {

    /** Validation outcome: an empty {@code findings} list means the collection is valid. */
    public record Result(List<String> findings, long utf8Size) {

        public Result {
            findings = List.copyOf(findings);
        }

        public boolean isValid() {
            return findings.isEmpty();
        }
    }

    private CollectionValidator() {
    }

    public static Result validate(PostmanCollection collection) {
        return validate(PostmanJson.toTree(collection));
    }

    /** Validate a collection document; the size returned is its compact UTF-8 size. */
    public static Result validate(JsonNode collection) {
        List<String> findings = new ArrayList<>();
        long size;
        try {
            size = PostmanJson.mapper().writeValueAsBytes(collection).length;
        } catch (JsonProcessingException e) {
            return new Result(List.of("collection is not JSON-serializable: " + e.getMessage()), 0);
        }
        if (collection == null || !collection.isObject()) {
            findings.add("missing info object");
            findings.add("item must be an array");
            return new Result(findings, size);
        }

        JsonNode info = collection.get("info");
        if (info == null || !info.isObject()) {
            findings.add("missing info object");
        } else {
            JsonNode name = info.get("name");
            if (name == null || !name.isTextual() || name.textValue().isEmpty()) {
                findings.add("info.name must be a non-empty string");
            }
            JsonNode description = info.get("description");
            if (description == null || !description.isTextual()) {
                findings.add("info.description must be a string");
            }
            JsonNode schema = info.get("schema");
            if (schema == null || !schema.isTextual() || schema.textValue().isEmpty()) {
                findings.add("info.schema must be a non-empty string");
            }
        }

        JsonNode item = collection.get("item");
        if (item == null || !item.isArray()) {
            findings.add("item must be an array");
        } else {
            checkItems(item, findings);
        }

        JsonNode variable = collection.get("variable");
        if (variable == null) {
            variable = PostmanJson.mapper().createArrayNode();
        }
        if (!variable.isArray()) {
            findings.add("variable must be an array");
        } else {
            for (int i = 0; i < variable.size(); i++) {
                JsonNode entry = variable.get(i);
                if (!entry.isObject()) {
                    findings.add("variable[" + i + "] must be an object");
                    continue;
                }
                JsonNode key = entry.get("key");
                if (key == null || !key.isTextual() || key.textValue().isEmpty()) {
                    findings.add("variable[" + i + "] key must be a non-empty string");
                }
                JsonNode value = entry.get("value");
                if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
                    findings.add("variable[" + i + "] value must be a non-empty string");
                }
            }
        }
        return new Result(findings, size);
    }

    private static void checkItems(JsonNode items, List<String> findings) {
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!item.isObject()) {
                findings.add("item[" + i + "] must be an object");
                continue;
            }
            if (item.has("item")) {
                JsonNode children = item.get("item");
                if (!children.isArray()) {
                    findings.add("item[" + i + "] 'item' must be an array");
                } else {
                    checkItems(children, findings);
                }
                continue;
            }
            JsonNode name = item.get("name");
            if (name == null || !name.isTextual() || name.textValue().isEmpty()) {
                findings.add("item[" + i + "] name must be a non-empty string");
            }
            JsonNode request = item.get("request");
            if (request == null || !request.isObject()) {
                findings.add("item[" + i + "] missing request object");
                continue;
            }
            JsonNode method = request.get("method");
            if (method == null || !method.isTextual() || method.textValue().isEmpty()) {
                findings.add("item[" + i + "] request.method must be a non-empty string");
            }
            JsonNode url = request.get("url");
            if (url == null || !url.isObject() || url.get("raw") == null || !url.get("raw").isTextual()) {
                findings.add("item[" + i + "] request.url.raw must be a string");
            }
            JsonNode header = request.get("header");
            if (header == null || !header.isArray()) {
                findings.add("item[" + i + "] request.header must be an array");
            } else {
                for (int j = 0; j < header.size(); j++) {
                    JsonNode entry = header.get(j);
                    if (!entry.isObject()) {
                        findings.add("item[" + i + "] header[" + j + "] must be an object");
                        continue;
                    }
                    JsonNode key = entry.get("key");
                    if (key == null || !key.isTextual() || key.textValue().isEmpty()) {
                        findings.add("item[" + i + "] header[" + j + "] key must be a non-empty string");
                    }
                    JsonNode value = entry.get("value");
                    if (value == null || !value.isTextual()) {
                        findings.add("item[" + i + "] header[" + j + "] value must be a string");
                    }
                }
            }
            JsonNode event = item.get("event");
            if (event != null) {
                checkEvents(i, event, findings);
            }
        }
    }

    private static void checkEvents(int itemIndex, JsonNode events, List<String> findings) {
        if (!events.isArray()) {
            findings.add("item[" + itemIndex + "] event must be an array");
            return;
        }
        for (int j = 0; j < events.size(); j++) {
            JsonNode event = events.get(j);
            if (!event.isObject()) {
                findings.add("item[" + itemIndex + "] event[" + j + "] must be an object");
                continue;
            }
            JsonNode listen = event.get("listen");
            if (listen == null || !listen.isTextual()
                    || !("test".equals(listen.textValue()) || "prerequest".equals(listen.textValue()))) {
                findings.add("item[" + itemIndex + "] event[" + j + "] listen must be 'test' or 'prerequest'");
            }
            JsonNode script = event.get("script");
            if (script == null || !script.isObject() || script.get("exec") == null || !script.get("exec").isArray()) {
                findings.add("item[" + itemIndex + "] event[" + j + "] script.exec must be an array");
                continue;
            }
            JsonNode exec = script.get("exec");
            for (int k = 0; k < exec.size(); k++) {
                if (!exec.get(k).isTextual()) {
                    findings.add("item[" + itemIndex + "] event[" + j
                            + "] script.exec must contain only strings");
                    break;
                }
            }
        }
    }
}
