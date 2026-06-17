package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.Map;

public record KnowledgeSkippedBreakdownView(
        Integer total,
        Map<String, Integer> byReason
) {
}
