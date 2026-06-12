package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeContextItemView(
        String sourceId,
        String displayName,
        String group,
        String relativePath,
        Integer lineStart,
        Integer lineEnd,
        String content,
        String matchType,
        String reason,
        Double score,
        KnowledgeContextMetadataView metadata
) {
}
