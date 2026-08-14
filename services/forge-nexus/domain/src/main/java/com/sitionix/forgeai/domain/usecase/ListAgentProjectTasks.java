package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskPage;
import java.util.UUID;

public interface ListAgentProjectTasks {

    AgentProjectTaskPage execute(UUID projectId, int page, int size);
}
