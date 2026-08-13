package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import java.util.UUID;

public interface CreateAgentProjectTask {

    AgentProjectTask execute(UUID projectId, CreateAgentProjectTaskCommand command);
}
