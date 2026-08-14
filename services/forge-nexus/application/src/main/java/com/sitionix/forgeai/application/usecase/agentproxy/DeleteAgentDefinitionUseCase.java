package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.DeleteAgentDefinition;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeleteAgentDefinitionUseCase implements DeleteAgentDefinition {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public void execute(final UUID agentId) {
        this.forgeAgentClient.deleteAgent(agentId);
    }
}
