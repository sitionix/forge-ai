package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ForgeAgentClientAdapter implements ForgeAgentClient {

    private final ForgeAgentHttpClient httpClient;
    private final ForgeAgentClientMapper mapper;
    private final ForgeAgentClientCallExecutor clientCallExecutor;

    @Override
    public List<AgentProject> listProjects() {
        return this.mapper.requireList(this.clientCallExecutor.execute(this.httpClient::listProjects), "projects").stream()
                .map(this.mapper::toDomain)
                .toList();
    }

    @Override
    public AgentProject createProject(final CreateAgentProjectCommand command) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.createProject(this.mapper.toRequest(command))));
    }

    @Override
    public List<AgentDefinitionListItem> listProjectAgents(final UUID projectId) {
        return this.mapper.requireList(this.clientCallExecutor.execute(() -> this.httpClient.listProjectAgents(projectId)), "agents").stream()
                .map(this.mapper::toDomain)
                .toList();
    }

    @Override
    public AgentDefinitionDetails createAgent(final UUID projectId, final SaveAgentDefinitionCommand command) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.createAgent(projectId, this.mapper.toRequest(command))));
    }

    @Override
    public AgentDefinitionDetails getAgent(final UUID agentId) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.getAgent(agentId)));
    }

    @Override
    public AgentDefinitionDetails updateAgent(final UUID agentId, final SaveAgentDefinitionCommand command) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.updateAgent(agentId, this.mapper.toRequest(command))));
    }
}
