package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import java.util.UUID;

public interface PullAgentProjectRepository {

    AgentProjectRepository execute(UUID projectId, UUID repositoryId);
}
