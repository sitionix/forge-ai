package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.DeleteAgentWorkflow;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeleteAgentWorkflowUseCase implements DeleteAgentWorkflow {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public void execute(final UUID workflowId) {
        this.forgeAgentClient.deleteWorkflow(workflowId);
    }
}
