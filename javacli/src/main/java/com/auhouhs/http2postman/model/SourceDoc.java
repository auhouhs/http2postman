/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of parsing a single {@code .http} / {@code .rest} source. */
public final class SourceDoc {

    private final Map<String, String> variables = new LinkedHashMap<>();
    private final List<DocItem> items = new ArrayList<>();
    private final List<UnsupportedItem> warnings = new ArrayList<>();

    public SourceDoc() {
    }

    public SourceDoc(List<DocItem> items) {
        this.items.addAll(items);
    }

    public Map<String, String> variables() {
        return Collections.unmodifiableMap(variables);
    }

    public List<DocItem> items() {
        return Collections.unmodifiableList(items);
    }

    public List<UnsupportedItem> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public void putVariable(String name, String value) {
        variables.put(name, value);
    }

    public void addItem(DocItem item) {
        items.add(item);
    }

    public void addWarning(UnsupportedItem warning) {
        warnings.add(warning);
    }
}
