package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.UpdateAgentWorkflow;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UpdateAgentWorkflowUseCase implements UpdateAgentWorkflow {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentWorkflow execute(final UUID workflowId, final SaveAgentWorkflowCommand command) {
        return this.forgeAgentClient.updateWorkflow(workflowId, command);
    }
}
