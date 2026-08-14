package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskPage;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ListAgentProjectTasks;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ListAgentProjectTasksUseCase implements ListAgentProjectTasks {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentProjectTaskPage execute(final UUID projectId, final int page, final int size) {
        return this.forgeAgentClient.listProjectTasks(projectId, page, size);
    }
}
