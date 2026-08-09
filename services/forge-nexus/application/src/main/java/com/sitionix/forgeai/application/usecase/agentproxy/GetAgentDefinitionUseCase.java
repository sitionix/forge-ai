package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.GetAgentDefinition;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetAgentDefinitionUseCase implements GetAgentDefinition {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentDefinitionDetails execute(final UUID agentId) {
        return this.forgeAgentClient.getAgent(agentId);
    }
}
