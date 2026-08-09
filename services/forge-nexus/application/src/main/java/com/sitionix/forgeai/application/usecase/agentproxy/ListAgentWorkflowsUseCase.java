package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ListAgentWorkflows;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ListAgentWorkflowsUseCase implements ListAgentWorkflows {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public List<AgentWorkflow> execute(final UUID projectId) {
        return this.forgeAgentClient.listProjectWorkflows(projectId);
    }
}
