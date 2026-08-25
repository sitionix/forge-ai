package com.sitionix.forgeagent.infrastructure.codex;

import java.util.Set;

final class CodexGenerationPolicy {

    static final String AGENT_MESSAGE = "agentMessage";
    private static final int ITEM_TYPE_DIAGNOSTIC_LIMIT = 128;

    private static final Set<String> ALLOWED_ITEM_TYPES = Set.of(
            "userMessage",
            "reasoning",
            AGENT_MESSAGE,
            "plan",
            "contextCompaction",
            "commandExecution",
            "fileChange"
    );

    RuntimeException violationFor(final String itemType) {
        if (itemType != null && ALLOWED_ITEM_TYPES.contains(itemType)) {
            return null;
        }
        return new CodexTransportException(
                "Unsupported Codex generation item type: " + this.diagnosticItemType(itemType)
        );
    }

    String diagnosticItemType(final String itemType) {
        if (itemType == null || itemType.isBlank()) {
            return "<missing>";
        }
        final String sanitized = itemType.replaceAll("[\\p{Cntrl}]", "?");
        return sanitized.length() <= ITEM_TYPE_DIAGNOSTIC_LIMIT
                ? sanitized
                : sanitized.substring(0, ITEM_TYPE_DIAGNOSTIC_LIMIT) + "...";
    }

    static boolean capturesFinalOutput(final String itemType) {
        return AGENT_MESSAGE.equals(itemType);
    }
}
