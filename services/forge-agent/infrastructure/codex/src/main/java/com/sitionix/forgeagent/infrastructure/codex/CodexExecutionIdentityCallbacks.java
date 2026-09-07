package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;

interface CodexExecutionIdentityCallbacks {
    default void executionStarted(final Runnable cancellation) { }
    void conversationStarted(String threadId, String providerVersion);
    void turnStarted(String turnId);
    default void executionEvent(final AgentExecutionEventCandidate event) { }
    default void eventCaptureCompleted(final AgentExecutionEventCandidate terminalEvent) { }
    default void eventCaptureDegraded(final RuntimeException failure) { }
}
