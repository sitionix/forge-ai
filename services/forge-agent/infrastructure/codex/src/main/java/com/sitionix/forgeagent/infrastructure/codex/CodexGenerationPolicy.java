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
    private final boolean sideEffectsAllowed;

    CodexGenerationPolicy(final boolean sideEffectsAllowed) {
        this.sideEffectsAllowed = sideEffectsAllowed;
    }

    RuntimeException violationFor(final String itemType) {
        if (this.sideEffectsAllowed) {
            return null;
        }
        if (itemType != null && SAFE_ITEM_TYPES.contains(itemType)) {
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
