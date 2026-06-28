package com.sitionix.forgeai.api;

import jakarta.validation.constraints.NotBlank;

public record JarvisKnowledgeQueryRequest(
        @NotBlank String query,
        String intent
) {
}
