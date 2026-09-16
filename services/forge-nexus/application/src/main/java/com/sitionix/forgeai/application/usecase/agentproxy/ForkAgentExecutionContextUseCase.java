package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentExecutionContext;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ForkAgentExecutionContext;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ForkAgentExecutionContextUseCase implements ForkAgentExecutionContext {
    private final ForgeAgentClient client;
    public List<AgentExecutionContext> execute(UUID sessionId) { return client.forkAgentExecutionContext(sessionId); }
}
