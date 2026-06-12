package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeContextBudgetView(
        Integer maxChars,
        Integer usedChars,
        Boolean truncated
) {
}
