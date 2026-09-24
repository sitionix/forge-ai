package com.sitionix.forgeagent.api.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record McpConnectionRequest(String displayName, URI endpoint, Transport transport, AuthType authType,
                                   ProjectAccess projectAccess, Set<String> allowedTools,
                                   CredentialChange credentialChange, @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) Credential credential) {
    public enum Transport { STREAMABLE_HTTP }
    public enum AuthType { NONE, BEARER, SECRET_HEADERS }
    public enum CredentialChange { KEEP, REPLACE, REMOVE }
    public record ProjectAccess(Scope scope, Set<UUID> projectIds) {
        public enum Scope { ALL, SELECTED }
    }
    public static final class Credential {
        private final String bearer;
        private final Map<String,String> headers;
        public Credential(@JsonProperty("bearer") String bearer, @JsonProperty("headers") Map<String,String> headers) {
            this.bearer=bearer; this.headers=headers;
        }
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public String bearer() { return bearer; }
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public Map<String,String> headers() { return headers; }
        @Override public String toString() { return "Credential[redacted]"; }
    }
    @Override public String toString() { return "McpConnectionRequest[redacted]"; }
}
