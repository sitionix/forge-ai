package com.sitionix.forgeai.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = false)
public record JarvisKnowledgeQueryRequest(
        @NotBlank String queryText,
        @NotNull JarvisKnowledgeQueryIntent intent,
        @NotBlank String answerLanguage,
        @NotNull Boolean includeTests,
        @NotNull Integer maxFlows
) {
}
