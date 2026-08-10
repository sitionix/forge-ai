package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.SaveAgentWorkflowRequest;
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
        final AgentProjectRequest request = this.mapper.toRequest(command);
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.createProject(request)));
    }

    @Override
    public List<AgentDefinitionListItem> listProjectAgents(final UUID projectId) {
        return this.mapper.requireList(this.clientCallExecutor.execute(() -> this.httpClient.listProjectAgents(projectId)), "agents").stream()
                .map(this.mapper::toDomain)
                .toList();
    }

    @Override
    public AgentDefinitionDetails createAgent(final UUID projectId, final SaveAgentDefinitionCommand command) {
        final AgentDefinitionRequest request = this.mapper.toRequest(command);
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.createAgent(projectId, request)));
    }

    @Override
    public AgentDefinitionDetails getAgent(final UUID agentId) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.getAgent(agentId)));
    }

    @Override
    public AgentDefinitionDetails updateAgent(final UUID agentId, final SaveAgentDefinitionCommand command) {
        final AgentDefinitionRequest request = this.mapper.toRequest(command);
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.updateAgent(agentId, request)));
    }

    @Override
    public List<AgentWorkflow> listProjectWorkflows(final UUID projectId) {
        return this.mapper.requireList(this.clientCallExecutor.execute(() -> this.httpClient.listProjectWorkflows(projectId)), "workflows").stream()
                .map(this.mapper::toDomain)
                .toList();
    }

    @Override
    public AgentWorkflow createWorkflow(final UUID projectId, final CreateAgentWorkflowCommand command) {
        final AgentWorkflowRequest request = this.mapper.toRequest(command);
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.createWorkflow(projectId, request)));
    }

    @Override
    public AgentWorkflow getWorkflow(final UUID workflowId) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.getWorkflow(workflowId)));
    }

    @Override
    public AgentWorkflow updateWorkflow(final UUID workflowId, final SaveAgentWorkflowCommand command) {
        final SaveAgentWorkflowRequest request = this.mapper.toRequest(command);
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.updateWorkflow(workflowId, request)));
    }

    @Override
    public AgentWorkflowRun createWorkflowRun(final UUID workflowId, final CreateAgentWorkflowRunCommand command) {
        final CreateWorkflowRunRequest request = this.mapper.toRequest(command);
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.createWorkflowRun(workflowId, request)));
    }

    @Override
    public List<AgentWorkflowRunSummary> listWorkflowRuns(final UUID workflowId) {
        return this.mapper.requireList(this.clientCallExecutor.execute(() -> this.httpClient.listWorkflowRuns(workflowId)), "workflow runs").stream()
                .map(this.mapper::toDomain)
                .toList();
    }

    @Override
    public AgentWorkflowRun getWorkflowRun(final UUID runId) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.getWorkflowRun(runId)));
    }
}
