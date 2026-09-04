package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;

interface CodexClient extends AutoCloseable {

    String execute(CodexTurnRequest request);

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

    String version();

    JsonNode request(String method, JsonNode params);

    @Override
    void close();
}
