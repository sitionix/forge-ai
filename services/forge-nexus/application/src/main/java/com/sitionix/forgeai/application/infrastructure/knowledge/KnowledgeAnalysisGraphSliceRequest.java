package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeAnalysisGraphSliceRequest(
        String sourceId,
        String rootGraphNodeId,
        String stableKey,
        String flowDomain,
        String direction,
        Integer depth,
        Integer maxNodes,
        Integer maxEdges,
        String includeExternal,
        Boolean includeUnresolved,
        Boolean includeTests,
        Boolean includeWorkflow,
        String edgeTypes,
        String nodeKinds,
        Boolean includeEvidence,
        Boolean includeClaims,
        Boolean includeIsolated
) {
}
