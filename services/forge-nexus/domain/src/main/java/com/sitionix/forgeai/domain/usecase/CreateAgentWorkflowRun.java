package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import java.util.UUID;

public interface CreateAgentWorkflowRun {

    AgentWorkflowRun execute(UUID workflowId, CreateAgentWorkflowRunCommand command);
}
