package com.sitionix.forgeagent.api.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record McpConnectionRequest(String displayName, URI endpoint, Transport transport, AuthType authType,
                                   ProjectAccess projectAccess, Set<String> allowedTools,
                                   CredentialChange credentialChange, @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) Credential credential,
                                   com.sitionix.forgeagent.domain.model.McpOAuthConfiguration oauthConfiguration) {
    public enum Transport { STREAMABLE_HTTP }
    public enum AuthType { NONE, BEARER, SECRET_HEADERS, OAUTH }
    public enum CredentialChange { KEEP, REPLACE, REMOVE }
    public record ProjectAccess(Scope scope, Set<UUID> projectIds) {
        public enum Scope { ALL, SELECTED }
    }
    public static final class Credential {
        private final String clientSecret;
        private final String bearer;
        private final Map<String,String> headers;
        public Credential(@JsonProperty("bearer") String bearer, @JsonProperty("headers") Map<String,String> headers, @JsonProperty("clientSecret") String clientSecret) {
            this.bearer=bearer; this.headers=headers; this.clientSecret=clientSecret;
        }
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public String bearer() { return bearer; }
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public Map<String,String> headers() { return headers; }
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public String clientSecret() { return clientSecret; }
        @Override public String toString() { return "Credential[redacted]"; }
    }
    @Override public String toString() { return "McpConnectionRequest[redacted]"; }
}
