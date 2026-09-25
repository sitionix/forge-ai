package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeagent.domain.model.McpRuntimeLaunchGrants;

interface CodexClient extends AutoCloseable {

    String execute(CodexTurnRequest request);

    default String execute(CodexTurnRequest request, McpRuntimeLaunchGrants grants) {
        if (!grants.isEmpty()) throw new CodexTransportException("Isolated Codex runtime is unavailable");
        return execute(request);
    }

    default String executeDurable(CodexTurnRequest request, String existingThreadId,
                                  CodexExecutionIdentityCallbacks callbacks) {
        return this.executeDurable(request, existingThreadId, null, callbacks);
    }

    default String executeDurable(CodexTurnRequest request, String existingThreadId, String expectedProviderVersion,
                                  CodexExecutionIdentityCallbacks callbacks) {
        throw new UnsupportedOperationException("Durable Codex execution is not supported.");
    }

    default String executeTrackedFresh(CodexTurnRequest request, CodexExecutionIdentityCallbacks callbacks) {
        throw new UnsupportedOperationException("Tracked Codex execution is not supported.");
    }

    default String executeTrackedFresh(CodexTurnRequest request, McpRuntimeLaunchGrants grants,
                                       CodexExecutionIdentityCallbacks callbacks) {
        if (!grants.isEmpty()) throw new CodexTransportException("Isolated Codex runtime is unavailable");
        return executeTrackedFresh(request, callbacks);
    }

    default String executeDurable(CodexTurnRequest request, String existingThreadId, String expectedProviderVersion,
                                  McpRuntimeLaunchGrants grants, CodexExecutionIdentityCallbacks callbacks) {
        if (!grants.isEmpty()) throw new CodexTransportException("Isolated Codex runtime is unavailable");
        return executeDurable(request, existingThreadId, expectedProviderVersion, callbacks);
    }

    String version();

    JsonNode request(String method, JsonNode params);

    @Override
    void close();
}
