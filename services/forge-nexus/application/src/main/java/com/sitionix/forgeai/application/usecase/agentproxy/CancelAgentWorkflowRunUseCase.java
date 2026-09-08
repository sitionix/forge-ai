package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.CancelAgentWorkflowRun;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CancelAgentWorkflowRunUseCase implements CancelAgentWorkflowRun {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public void execute(final UUID runId) {
        this.forgeAgentClient.cancelWorkflowRun(runId);
    }
}
