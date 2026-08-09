package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ListProjectAgentDefinitions;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ListProjectAgentDefinitionsUseCase implements ListProjectAgentDefinitions {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public List<AgentDefinitionListItem> execute(final UUID projectId) {
        return this.forgeAgentClient.listProjectAgents(projectId);
    }
}
