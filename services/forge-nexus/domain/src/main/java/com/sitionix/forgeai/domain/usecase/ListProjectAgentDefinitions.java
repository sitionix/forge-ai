package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import java.util.List;
import java.util.UUID;

public interface ListProjectAgentDefinitions {

    List<AgentDefinitionListItem> execute(UUID projectId);
}
