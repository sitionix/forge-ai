package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ListAgentWorkflowRuns;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ListAgentWorkflowRunsUseCase implements ListAgentWorkflowRuns {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public List<AgentWorkflowRunSummary> execute(final UUID workflowId) {
        return this.forgeAgentClient.listWorkflowRuns(workflowId);
    }
}
