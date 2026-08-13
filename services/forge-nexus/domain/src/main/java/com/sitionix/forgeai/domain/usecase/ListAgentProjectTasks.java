package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskSummary;
import java.util.List;
import java.util.UUID;

public interface ListAgentProjectTasks {

    List<AgentProjectTaskSummary> execute(UUID projectId);
}
