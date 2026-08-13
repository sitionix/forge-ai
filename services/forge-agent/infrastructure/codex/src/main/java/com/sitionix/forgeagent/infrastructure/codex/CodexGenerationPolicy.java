package com.sitionix.forgeagent.infrastructure.codex;

import java.util.Set;

final class CodexGenerationPolicy {

    static final String AGENT_MESSAGE = "agentMessage";

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
        return executionFailed();
    }

    private RuntimeException executionFailed() {
        return new CodexTransportException("Codex execution failed.");
    }

    static boolean capturesFinalOutput(final String itemType) {
        return AGENT_MESSAGE.equals(itemType);
    }
}
