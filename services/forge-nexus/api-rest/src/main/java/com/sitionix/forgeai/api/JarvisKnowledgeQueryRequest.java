package com.sitionix.forgeai.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
public record JarvisKnowledgeQueryRequest(
        @NotBlank String queryText,
        JarvisKnowledgeQueryIntent intent,
        String answerLanguage,
        Boolean includeTests,
        @Min(1) @Max(10) Integer maxFlows
) {
    @JsonAnySetter
    public void rejectUnknownField(final String fieldName, final Object ignoredValue) {
        throw new IllegalArgumentException("Unknown query request field: " + fieldName);
    }
}
