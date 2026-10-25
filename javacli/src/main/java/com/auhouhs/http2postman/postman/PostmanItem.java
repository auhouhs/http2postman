/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.postman;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One collection entry: either a request ({@code request} set) or a folder
 * ({@code item} set).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostmanItem(
        String name,
        PostmanRequest request,
        List<PostmanItem> item,
        PostmanAuth auth,
        List<PostmanEvent> event) {

    public static PostmanItem folder(String name, List<PostmanItem> children) {
        return new PostmanItem(name, null, children, null, null);
    }

    public static PostmanItem request(String name, PostmanRequest request, PostmanAuth auth,
                                      List<PostmanEvent> events) {
        return new PostmanItem(name, request, null, auth, events);
    }

    public boolean isFolder() {
        return item != null;
    }
}
