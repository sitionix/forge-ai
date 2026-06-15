package com.sitionix.forgeai.domain.model.jarvis;

public record JarvisChatContextView(
        String sourceId,
        String displayName,
        String relativePath,
        Integer lineStart,
        Integer lineEnd,
        String reason,
        Double score
) {
}
