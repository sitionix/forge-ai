package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskSummary;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ListAgentProjectTasks;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ListAgentProjectTasksUseCase implements ListAgentProjectTasks {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public List<AgentProjectTaskSummary> execute(final UUID projectId) {
        return this.forgeAgentClient.listProjectTasks(projectId);
    }
}
