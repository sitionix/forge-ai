package com.sitionix.forgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record JarvisKnowledgeQueryResponse(
        String queryId,
        String status,
        String intent,
        List<JsonNode> matchedSources,
        List<JsonNode> anchors,
        List<JsonNode> nodes,
        List<JsonNode> edges,
        List<JsonNode> verifiedPaths,
        List<JsonNode> evidence,
        List<JsonNode> unresolved,
        List<JsonNode> external,
        JsonNode coverage,
        List<JsonNode> diagnostics
) {
}
