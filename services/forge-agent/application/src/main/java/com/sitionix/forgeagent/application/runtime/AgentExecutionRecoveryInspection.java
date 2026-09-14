package com.sitionix.forgeagent.application.runtime;

import java.time.Instant;

public record AgentExecutionRecoveryInspection(
        String providerId,
        String providerVersion,
        String providerConversationId,
        String providerTurnId,
        ExecutionWorkspace executionWorkspace,
        Instant deadline
) {
}
