package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.GetAgentWorkflowRun;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetAgentWorkflowRunUseCase implements GetAgentWorkflowRun {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentWorkflowRun execute(final UUID runId) {
        return this.forgeAgentClient.getWorkflowRun(runId);
    }
}
