package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeContextRequest(
        String query,
        List<String> sourceIds,
        List<String> groups,
        Integer maxChars,
        Integer maxItems,
        Boolean includeContent
) {
}
