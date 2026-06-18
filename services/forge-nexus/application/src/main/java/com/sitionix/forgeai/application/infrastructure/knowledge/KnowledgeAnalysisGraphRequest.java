package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeAnalysisGraphRequest(
        String sourceId,
        String graphNodeId,
        String graphEdgeId,
        String inventoryFileId,
        String flowDomain,
        String factOrigin,
        String nodeKind,
        String edgeType,
        Integer depth,
        Integer limit,
        Boolean includeEvidence,
        Boolean includeClaims,
        Boolean includeDiagnostics
) {
}
