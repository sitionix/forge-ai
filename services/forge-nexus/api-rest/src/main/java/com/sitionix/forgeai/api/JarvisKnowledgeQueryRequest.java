package com.sitionix.forgeai.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record JarvisKnowledgeQueryRequest(
        @NotBlank String query,
        String intent,
        @Min(1) @Max(20) Integer maxAnchors,
        @Min(1) @Max(4) Integer depth
) {
}
