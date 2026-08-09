package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.CreateAgentWorkflow;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreateAgentWorkflowUseCase implements CreateAgentWorkflow {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentWorkflow execute(final UUID projectId, final CreateAgentWorkflowCommand command) {
        return this.forgeAgentClient.createWorkflow(projectId, command);
    }
}
