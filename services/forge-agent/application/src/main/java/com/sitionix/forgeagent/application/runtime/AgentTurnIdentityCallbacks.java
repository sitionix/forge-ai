package com.sitionix.forgeagent.application.runtime;
public interface AgentTurnIdentityCallbacks {
    default void executionStarted(Runnable cancel) { }
    default void executionFinished(boolean cleanupConfirmed) { }
    default void conversationStarted(String id, String version) { }
    default void turnStarted(String id) { }
    default void dispatchTurnStart(Runnable write) { write.run(); }
    static AgentTurnIdentityCallbacks noop() { return new AgentTurnIdentityCallbacks() { }; }
}
