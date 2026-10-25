/*
 * http2postman (Java port) - MIT licensed, see LICENSE / THIRD_PARTY_NOTICES.md.
 * Ported from https://github.com/salitaba/http2postman, (c) 2026 Ali Tabatabaei.
 */
package com.auhouhs.http2postman.cli;

/** Jar entry point. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(Cli.run(args, System.out, System.err));
    }
}
