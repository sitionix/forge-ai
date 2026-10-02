package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.Set;

/** Provider-observed metadata. Null expiry/scope is unknown, never a library default. */
public final class McpOAuthTokens {
    private final String accessToken;
    private final String refreshToken;
    private final Instant expiresAt;
    private final Instant refreshExpiresAt;
    private final Set<String> grantedScopes;
    public McpOAuthTokens(String accessToken, String refreshToken, Instant expiresAt, Instant refreshExpiresAt, Set<String> grantedScopes) {
        if (accessToken == null || accessToken.isBlank() || accessToken.contains("\r") || accessToken.contains("\n")
                || (refreshToken != null && refreshToken.isBlank())) throw new IllegalArgumentException("Invalid OAuth tokens");
        this.accessToken = accessToken; this.refreshToken = refreshToken;
        this.expiresAt = expiresAt; this.refreshExpiresAt = refreshExpiresAt;
        this.grantedScopes = grantedScopes == null ? null : Set.copyOf(grantedScopes);
    }
    public String accessToken() { return accessToken; }
    public String refreshToken() { return refreshToken; }
    public Instant expiresAt() { return expiresAt; }
    public Instant refreshExpiresAt() { return refreshExpiresAt; }
    public Set<String> grantedScopes() { return grantedScopes; }
    @Override public String toString() { return "McpOAuthTokens[redacted]"; }
}
