package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import java.util.UUID;

public interface CreateAgentWorkflow {

    AgentWorkflow execute(UUID projectId, CreateAgentWorkflowCommand command);
}
