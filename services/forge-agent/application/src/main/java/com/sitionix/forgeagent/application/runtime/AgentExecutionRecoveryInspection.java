package com.sitionix.forgeagent.application.runtime;

public record AgentExecutionRecoveryInspection(
        String providerId,
        String providerVersion,
        String providerConversationId,
        String providerTurnId,
        ExecutionWorkspace executionWorkspace
) {
}
