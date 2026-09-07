package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentExecutionContext;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.GetAgentExecutionContexts;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class GetAgentExecutionContextsUseCase implements GetAgentExecutionContexts {
    private final ForgeAgentClient client;
    public List<AgentExecutionContext> execute(UUID runId) { return client.getAgentExecutionContexts(runId); }
}
