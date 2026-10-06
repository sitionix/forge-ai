package com.sitionix.forgeagent.domain.model;

import java.net.URI;
import java.util.Objects;

public final class McpOAuthAuthorization {
    private final URI authorizationUrl;
    private final String verifier;
    public McpOAuthAuthorization(URI authorizationUrl, String verifier) {
        this.authorizationUrl = Objects.requireNonNull(authorizationUrl);
        this.verifier = Objects.requireNonNull(verifier);
    }
    public URI authorizationUrl() { return authorizationUrl; }
    public String verifier() { return verifier; }
    @Override public String toString() { return "McpOAuthAuthorization[redacted]"; }
}
