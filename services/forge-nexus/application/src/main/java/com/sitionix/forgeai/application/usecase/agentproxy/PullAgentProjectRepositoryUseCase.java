package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.PullAgentProjectRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PullAgentProjectRepositoryUseCase implements PullAgentProjectRepository {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentProjectRepository execute(final UUID projectId, final UUID repositoryId) {
        return this.forgeAgentClient.pullProjectRepository(projectId, repositoryId);
    }
}
