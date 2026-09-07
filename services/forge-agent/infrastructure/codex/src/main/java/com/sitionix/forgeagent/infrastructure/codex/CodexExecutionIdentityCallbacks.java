package com.sitionix.forgeagent.infrastructure.codex;

interface CodexExecutionIdentityCallbacks {
    default void executionStarted(final Runnable cancellation) { }
    void conversationStarted(String threadId, String providerVersion);
    void turnStarted(String turnId);
}
