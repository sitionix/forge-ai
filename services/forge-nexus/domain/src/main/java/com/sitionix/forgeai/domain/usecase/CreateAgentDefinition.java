package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import java.util.UUID;

public interface CreateAgentDefinition {

    AgentDefinitionDetails execute(UUID projectId, SaveAgentDefinitionCommand command);
}
