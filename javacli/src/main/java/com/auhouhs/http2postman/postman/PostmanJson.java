/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** JSON plumbing for the Postman collection document. */
public final class PostmanJson {

    /** Schema URL written into {@code info.schema}. */
    public static final String SCHEMA_URL =
            "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PostmanJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /** Serialize with two-space indentation and {@code key: value} spacing, like the Python tool. */
    public static String toPrettyString(JsonNode node) throws JsonProcessingException {
        return MAPPER.writer(new CollectionPrettyPrinter()).writeValueAsString(node);
    }

    /** UTF-8 size of the compact form, used for the 2 MB split threshold. */
    public static long compactSize(JsonNode node) throws JsonProcessingException {
        return MAPPER.writeValueAsBytes(node).length;
    }

    /** Two-space indentation, LF line feed and {@code key: value} spacing (Python parity). */
    private static final class CollectionPrettyPrinter extends DefaultPrettyPrinter {

        private CollectionPrettyPrinter() {
            _objectIndenter = new DefaultIndenter("  ", "\n");
            _arrayIndenter = new DefaultIndenter("  ", "\n");
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new CollectionPrettyPrinter();
        }

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator generator) throws IOException {
            generator.writeRaw(": ");
        }
    }
}
