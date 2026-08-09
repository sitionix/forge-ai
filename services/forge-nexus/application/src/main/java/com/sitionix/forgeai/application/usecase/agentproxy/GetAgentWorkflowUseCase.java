package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.GetAgentWorkflow;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetAgentWorkflowUseCase implements GetAgentWorkflow {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentWorkflow execute(final UUID workflowId) {
        return this.forgeAgentClient.getWorkflow(workflowId);
    }
}
