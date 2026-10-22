package com.sitionix.forgeagent.application.runtime;
import java.util.UUID;
public interface AgentTurnExecutionPort {
    AgentTurnResult execute(AgentTurnExecutionRequest request, AgentTurnEventSink events);
    void interrupt(UUID executionId);
}
