package com.sitionix.forgeagent.infrastructure.codex;

import java.util.Set;

final class CodexGenerationPolicy {

    static final String AGENT_MESSAGE = "agentMessage";

    private static final Set<String> SAFE_ITEM_TYPES = Set.of(
            "userMessage",
            "reasoning",
            AGENT_MESSAGE,
            "plan",
            "contextCompaction"
    );
    private CodexGenerationPolicy() {
    }

    static RuntimeException violationFor(final String itemType) {
        if (itemType != null && SAFE_ITEM_TYPES.contains(itemType)) {
            return null;
        }
        return executionFailed();
    }

    private static RuntimeException executionFailed() {
        return new CodexTransportException("Codex execution failed.");
    }

    static boolean capturesFinalOutput(final String itemType) {
        return AGENT_MESSAGE.equals(itemType);
    }
}
