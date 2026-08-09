package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.UpdateAgentDefinition;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UpdateAgentDefinitionUseCase implements UpdateAgentDefinition {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentDefinitionDetails execute(final UUID agentId, final SaveAgentDefinitionCommand command) {
        return this.forgeAgentClient.updateAgent(agentId, command);
    }
}
