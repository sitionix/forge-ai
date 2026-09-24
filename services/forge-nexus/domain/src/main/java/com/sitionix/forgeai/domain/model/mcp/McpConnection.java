package com.sitionix.forgeai.domain.model.mcp;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record McpConnection(UUID id, String displayName, URI endpoint, Transport transport, AuthType authType,
                            boolean enabled, ProjectAccess projectAccess, Set<AllowedTool> allowedTools,
                            boolean credentialConfigured, Instant createdAt, Instant updatedAt,
                            Instant checkedAt, String safeDiagnostic) {
    public enum Transport { STREAMABLE_HTTP }
    public enum AuthType { NONE, BEARER, SECRET_HEADERS }
    public enum Scope { ALL, SELECTED }
    public enum CredentialChange { KEEP, REPLACE, REMOVE }
    public record AllowedTool(String name,String schemaFingerprint){}
    public record ProjectAccess(Scope scope, Set<UUID> projectIds) {
        public ProjectAccess { if (scope==null || projectIds==null || projectIds.stream().anyMatch(java.util.Objects::isNull) || (scope==Scope.ALL && !projectIds.isEmpty())) throw new IllegalArgumentException("Invalid project access"); projectIds=Set.copyOf(projectIds); }
    }
}
