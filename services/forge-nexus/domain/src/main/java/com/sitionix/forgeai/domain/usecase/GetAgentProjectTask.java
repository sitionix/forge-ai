package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import java.util.UUID;

public interface GetAgentProjectTask {

    AgentProjectTask execute(UUID taskId);
}
