package com.sitionix.forgeagent.infrastructure.codex;

interface CodexExecutionIdentityCallbacks {
    void conversationStarted(String threadId, String providerVersion);
    void turnStarted(String turnId);
}
