package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.model.agentproxy.ImportAgentProjectRepositoryCommand;
import java.util.UUID;

public interface ImportAgentProjectRepository {

    AgentProjectRepository execute(UUID projectId, ImportAgentProjectRepositoryCommand command);
}
