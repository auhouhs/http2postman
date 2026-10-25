/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A {@code ## Folder} / {@code @group} block. */
public final class Folder implements DocItem {

    private final String name;
    private final List<DocItem> items = new ArrayList<>();

    public Folder(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public List<DocItem> items() {
        return Collections.unmodifiableList(items);
    }

    public void add(DocItem item) {
        items.add(item);
    }
}
