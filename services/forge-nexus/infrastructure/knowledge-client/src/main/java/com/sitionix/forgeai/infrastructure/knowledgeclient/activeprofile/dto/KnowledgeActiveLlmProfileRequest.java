package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record KnowledgeActiveLlmProfileRequest(
        long expectedRevision,
        String providerId,
        String modelId,
        KnowledgeActiveLlmEffort effort
) {
}
