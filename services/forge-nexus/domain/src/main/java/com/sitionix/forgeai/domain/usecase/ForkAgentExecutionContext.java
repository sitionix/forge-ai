package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentExecutionContext;
import java.util.List;
import java.util.UUID;

public interface ForkAgentExecutionContext { List<AgentExecutionContext> execute(UUID sessionId); }
