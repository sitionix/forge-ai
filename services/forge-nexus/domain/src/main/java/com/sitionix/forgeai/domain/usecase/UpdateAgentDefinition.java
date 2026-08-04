package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import java.util.UUID;

public interface UpdateAgentDefinition {

    AgentDefinitionDetails execute(UUID agentId, SaveAgentDefinitionCommand command);
}
