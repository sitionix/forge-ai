package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentExecutionEventPage;
import java.util.UUID;

public interface GetAgentExecutionEvents {
    AgentExecutionEventPage execute(UUID turnId, long afterSequence, int limit);
}
