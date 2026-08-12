package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.application.runtime.AgentExecutionException;
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

    static AgentExecutionException violationFor(final String itemType) {
        if (itemType != null && SAFE_ITEM_TYPES.contains(itemType)) {
            return null;
        }
        return executionFailed();
    }

    private static AgentExecutionException executionFailed() {
        return new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed.");
    }

    static boolean capturesFinalOutput(final String itemType) {
        return AGENT_MESSAGE.equals(itemType);
    }
}
