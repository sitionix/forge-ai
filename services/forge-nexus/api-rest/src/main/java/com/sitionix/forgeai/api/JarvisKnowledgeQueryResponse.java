package com.sitionix.forgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record JarvisKnowledgeQueryResponse(
        String queryId,
        String status,
        String intent,
        List<JsonNode> matchedSources,
        List<JsonNode> matchedNodes,
        List<JsonNode> flows,
        JsonNode coverage,
        List<JsonNode> diagnostics
) {
}
