package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.CreateAgentDefinition;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreateAgentDefinitionUseCase implements CreateAgentDefinition {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentDefinitionDetails execute(final UUID projectId, final SaveAgentDefinitionCommand command) {
        return this.forgeAgentClient.createAgent(projectId, command);
    }
}
