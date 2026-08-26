package com.sitionix.forgeagent.application.runtime;

public interface AgentExecutor {

    AgentExecutionResult execute(NodeExecutionClaim claim);
}
