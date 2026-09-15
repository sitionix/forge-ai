package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.RecoveredAgentNodeRunRetry;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.RetryRecoveredAgentNodeRun;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetryRecoveredAgentNodeRunUseCase implements RetryRecoveredAgentNodeRun {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public RecoveredAgentNodeRunRetry execute(final UUID workflowRunId, final UUID nodeRunId) {
        return this.forgeAgentClient.retryRecoveredNodeRun(workflowRunId, nodeRunId);
    }
}
