package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import java.util.UUID;

public interface GetAgentWorkflowRun {

    AgentWorkflowRun execute(UUID runId);
}
