package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.CreateAgentWorkflowRun;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreateAgentWorkflowRunUseCase implements CreateAgentWorkflowRun {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentWorkflowRun execute(final UUID workflowId, final CreateAgentWorkflowRunCommand command) {
        return this.forgeAgentClient.createWorkflowRun(workflowId, command);
    }
}
