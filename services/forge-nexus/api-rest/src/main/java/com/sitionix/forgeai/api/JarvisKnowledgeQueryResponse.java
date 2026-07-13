package com.sitionix.forgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record JarvisKnowledgeQueryResponse(
        String answerLanguage,
        List<JsonNode> answers,
        List<JsonNode> diagnostics
) {
}
