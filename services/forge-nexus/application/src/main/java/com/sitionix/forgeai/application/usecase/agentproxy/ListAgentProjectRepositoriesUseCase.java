package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ListAgentProjectRepositories;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ListAgentProjectRepositoriesUseCase implements ListAgentProjectRepositories {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public List<AgentProjectRepository> execute(final UUID projectId) {
        return this.forgeAgentClient.listProjectRepositories(projectId);
    }
}
