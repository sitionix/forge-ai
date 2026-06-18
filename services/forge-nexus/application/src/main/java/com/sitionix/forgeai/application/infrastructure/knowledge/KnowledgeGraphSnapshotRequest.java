package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeGraphSnapshotRequest(
        String sourceId,
        String flowDomain,
        String factOrigin,
        String nodeKind,
        String edgeType,
        String includeExternal,
        Boolean includeUnresolved,
        Boolean includeIsolated,
        String graphRevision,
        String cursor,
        Integer pageSize,
        String ifNoneMatch
) {
}
