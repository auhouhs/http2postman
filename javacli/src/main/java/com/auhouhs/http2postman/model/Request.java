/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One parsed HTTP request. */
public final class Request implements DocItem {

    private final String name;
    private final String method;
    private final String url;
    private final HttpVersion httpVersion;
    private final List<Header> headers;
    private final Body body;
    private final String sourceFile;
    private final int sourceLine;
    private final List<Script> preScripts = new ArrayList<>();
    private final List<Script> postScripts = new ArrayList<>();

    public Request(String name, String method, String url, List<Header> headers, Body body,
                   String sourceFile, int sourceLine) {
        this(name, method, url, headers, body, null, sourceFile, sourceLine);
    }

    public Request(String name, String method, String url, List<Header> headers, Body body,
                   HttpVersion httpVersion, String sourceFile, int sourceLine) {
        this.name = name == null ? "" : name;
        this.method = method;
        this.url = url;
        this.httpVersion = httpVersion;
        this.headers = List.copyOf(headers);
        this.body = body;
        this.sourceFile = sourceFile;
        this.sourceLine = sourceLine;
    }

    public String name() {
        return name;
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    /** HTTP version pinned on the request line, {@code null} when the file left it to negotiation. */
    public HttpVersion httpVersion() {
        return httpVersion;
    }

    public List<Header> headers() {
        return headers;
    }

    /** Parsed body, {@code null} when the request has none. */
    public Body body() {
        return body;
    }

    public String sourceFile() {
        return sourceFile;
    }

    public int sourceLine() {
        return sourceLine;
    }

    public List<Script> preScripts() {
        return Collections.unmodifiableList(preScripts);
    }

    public List<Script> postScripts() {
        return Collections.unmodifiableList(postScripts);
    }

    public void addPreScript(Script script) {
        preScripts.add(script);
    }

    public void addPostScript(Script script) {
        postScripts.add(script);
    }
}
