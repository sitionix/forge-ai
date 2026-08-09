package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import java.util.UUID;

public interface UpdateAgentWorkflow {

    AgentWorkflow execute(UUID workflowId, SaveAgentWorkflowCommand command);
}
