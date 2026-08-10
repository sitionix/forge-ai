package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import java.util.UUID;

public interface GetAgentWorkflow {

    AgentWorkflow execute(UUID workflowId);
}
