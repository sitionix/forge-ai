package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentExecutionContext;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ResetAgentExecutionContext;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ResetAgentExecutionContextUseCase implements ResetAgentExecutionContext {
    private final ForgeAgentClient client;
    public List<AgentExecutionContext> execute(UUID sessionId) { return client.resetAgentExecutionContext(sessionId); }
}
