package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

import java.util.List;

public record KnowledgeLlmUsage(List<KnowledgeLlmUsageWindow> windows) {
    public KnowledgeLlmUsage {
        if (windows == null) {
            throw new IllegalArgumentException("windows is required");
        }
        windows = List.copyOf(windows);
    }
}
