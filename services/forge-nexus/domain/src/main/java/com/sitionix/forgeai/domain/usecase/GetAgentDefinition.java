package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import java.util.UUID;

public interface GetAgentDefinition {

    AgentDefinitionDetails execute(UUID agentId);
}
