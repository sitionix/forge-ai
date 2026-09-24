package com.sitionix.forgeagent.domain.model;

import java.util.Set;
import java.util.UUID;

public record McpProjectAccess(Scope scope, Set<UUID> projectIds) {
    public enum Scope { ALL, SELECTED }
    public McpProjectAccess {
        if (scope == null || projectIds == null || (scope == Scope.ALL && !projectIds.isEmpty()) || projectIds.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Invalid project access");
        projectIds = Set.copyOf(projectIds);
    }
    public static McpProjectAccess all() { return new McpProjectAccess(Scope.ALL, Set.of()); }
    public static McpProjectAccess selected(Set<UUID> ids) { return new McpProjectAccess(Scope.SELECTED, ids); }
    public boolean allows(UUID id) { return scope == Scope.ALL || projectIds.contains(id); }
}
