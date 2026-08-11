package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.GetAgentRuntime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetAgentRuntimeUseCase implements GetAgentRuntime {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentRuntimeCatalog execute() {
        return this.forgeAgentClient.getRuntime();
    }
}
