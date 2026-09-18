package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.SelectAgentManualNodeOutput;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SelectAgentManualNodeOutputUseCase implements SelectAgentManualNodeOutput {
    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentWorkflowRun execute(final UUID workflowRunId, final UUID nodeRunId, final UUID outputPortId) {
        return this.forgeAgentClient.selectManualNodeOutput(workflowRunId, nodeRunId, outputPortId);
    }
}
