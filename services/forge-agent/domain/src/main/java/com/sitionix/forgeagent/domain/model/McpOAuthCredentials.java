package com.sitionix.forgeagent.domain.model;

/** Internal secret envelope; serializable only by the encrypted-storage adapter. */
public final class McpOAuthCredentials {
    private final String clientSecret;
    private final McpOAuthTokens tokens;
    public McpOAuthCredentials(String clientSecret, McpOAuthTokens tokens) {
        if (clientSecret != null && clientSecret.isBlank()) throw new IllegalArgumentException("Invalid OAuth client credential");
        this.clientSecret = clientSecret; this.tokens = tokens;
    }
    public String clientSecret() { return clientSecret; }
    public McpOAuthTokens tokens() { return tokens; }
    @Override public String toString() { return "McpOAuthCredentials[redacted]"; }
}
