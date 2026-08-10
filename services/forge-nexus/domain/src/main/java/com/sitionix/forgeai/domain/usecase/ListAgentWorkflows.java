package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import java.util.List;
import java.util.UUID;

public interface ListAgentWorkflows {

    List<AgentWorkflow> execute(UUID projectId);
}
