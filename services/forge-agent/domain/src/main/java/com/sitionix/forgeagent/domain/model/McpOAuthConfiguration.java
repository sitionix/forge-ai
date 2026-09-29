package com.sitionix.forgeagent.domain.model;

import java.net.URI;
import java.util.Set;

/** Pre-registered public client metadata. Client secrets belong to encrypted credentials. */
public record McpOAuthConfiguration(URI issuer, URI authorizationEndpoint, URI tokenEndpoint,
                                    URI revocationEndpoint, String clientId, String clientAuthenticationMethod,
                                    Set<String> scopes, URI resource) {
    public McpOAuthConfiguration {
        validateUri(issuer); validateUri(authorizationEndpoint); validateUri(tokenEndpoint); validateUri(resource);
        if (revocationEndpoint != null) validateUri(revocationEndpoint);
        if (clientId == null || clientId.isBlank() || clientId.contains("\r") || clientId.contains("\n")
                || !Set.of("none", "client_secret_post", "client_secret_basic").contains(clientAuthenticationMethod)
                || scopes == null || scopes.stream().anyMatch(s -> s == null || !s.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+")))
            throw new IllegalArgumentException("Invalid OAuth configuration");
        scopes = Set.copyOf(scopes);
    }
    public static void validateUri(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getRawQuery() != null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("Invalid OAuth endpoint");
    }
}
