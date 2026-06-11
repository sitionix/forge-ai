package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeContextMetadataView(
        List<String> tags,
        List<String> domainKeywords,
        List<String> ownsBusinessAreas
) {
}
