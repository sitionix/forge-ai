package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record KnowledgeActiveProfileResponse(
        long revision,
        KnowledgeActiveLlmProfileDetails llmProfile,
        KnowledgeLlmUsage usage
) {
}
