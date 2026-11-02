package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.http;

import java.net.URI;

/** Test-only access to the package-local literal IPv4 loopback seam. */
public final class LoopbackOpenApiFixture {
    private LoopbackOpenApiFixture() { }
    public static PinnedOpenApiEffect select(String document, String digest, String operation, int port) {
        return new PinnedOpenApiEffect(document, digest, operation, URI.create("http://127.0.0.1:" + port), true);
    }
}
