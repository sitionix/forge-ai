package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;

public interface CreateAgentProject {

    AgentProject execute(CreateAgentProjectCommand command);
}
