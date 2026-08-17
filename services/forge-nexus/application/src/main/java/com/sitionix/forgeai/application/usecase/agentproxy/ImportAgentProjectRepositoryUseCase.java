package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.model.agentproxy.ImportAgentProjectRepositoryCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ImportAgentProjectRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ImportAgentProjectRepositoryUseCase implements ImportAgentProjectRepository {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentProjectRepository execute(final UUID projectId, final ImportAgentProjectRepositoryCommand command) {
        return this.forgeAgentClient.importProjectRepository(projectId, command);
    }
}
