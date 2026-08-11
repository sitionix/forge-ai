package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRunOutput;

public interface AgentExecutor {

    NodeRunOutput execute(NodeExecutionClaim claim);
}
