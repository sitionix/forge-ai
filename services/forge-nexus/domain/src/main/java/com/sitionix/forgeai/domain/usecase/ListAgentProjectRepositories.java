package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import java.util.List;
import java.util.UUID;

public interface ListAgentProjectRepositories {

    List<AgentProjectRepository> execute(UUID projectId);
}
