package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.domain.model.agentproxy.*;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskPage;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.ImportAgentProjectRepositoryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogDiscoveryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogSourceRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentSshConnectionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateProjectTaskRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ImportProjectRepositoryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.SaveAgentWorkflowRequest;
import java.io.OutputStream;
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
  private final ForgeAgentLogStreamingHttpClient logStreamingHttpClient;

  @Override
  public List<AgentProject> listProjects() {
    final var response = this.clientCallExecutor.execute(this.httpClient::listProjects);
    return response == null ? null : response.stream().map(this.mapper::toDomain).toList();
  }

  @Override
  public AgentProject createProject(final CreateAgentProjectCommand command) {
    final AgentProjectRequest request = this.mapper.toRequest(command);
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(() -> this.httpClient.createProject(request)));
  }

  @Override
  public void deleteProject(final UUID projectId) {
    this.clientCallExecutor.execute(
        () -> {
          this.httpClient.deleteProject(projectId);
          return null;
        });
  }

  @Override
  public AgentProjectRepository importProjectRepository(
      final UUID projectId, final ImportAgentProjectRepositoryCommand command) {
    final ImportProjectRepositoryRequest request = this.mapper.toRequest(command);
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(
            () -> this.httpClient.importProjectRepository(projectId, request)));
  }

  @Override
  public List<AgentProjectRepository> listProjectRepositories(final UUID projectId) {
    final var response =
        this.clientCallExecutor.execute(() -> this.httpClient.listProjectRepositories(projectId));
    return response == null ? null : response.stream().map(this.mapper::toDomain).toList();
  }

  @Override
  public AgentProjectRepository cloneProjectRepository(
      final UUID projectId, final UUID repositoryId) {
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(
            () -> this.httpClient.cloneProjectRepository(projectId, repositoryId)));
  }

  @Override
  public AgentProjectRepository refreshProjectRepository(
      final UUID projectId, final UUID repositoryId) {
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(
            () -> this.httpClient.refreshProjectRepository(projectId, repositoryId)));
  }

  @Override
  public AgentProjectRepository pullProjectRepository(
      final UUID projectId, final UUID repositoryId) {
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(
            () -> this.httpClient.pullProjectRepository(projectId, repositoryId)));
  }

  @Override
  public AgentProjectTask createProjectTask(
      final UUID projectId, final CreateAgentProjectTaskCommand command) {
    final CreateProjectTaskRequest request = this.mapper.toRequest(command);
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(
            () -> this.httpClient.createProjectTask(projectId, request)));
  }

  @Override
  public AgentProjectTaskPage listProjectTasks(
      final UUID projectId, final int page, final int size) {
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(
            () -> this.httpClient.listProjectTasks(projectId, page, size)));
  }

  @Override
  public AgentProjectTask getProjectTask(final UUID taskId) {
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(() -> this.httpClient.getProjectTask(taskId)));
  }

  @Override
  public void deleteProjectTask(final UUID taskId) {
    this.clientCallExecutor.execute(
        () -> {
          this.httpClient.deleteProjectTask(taskId);
          return null;
        });
  }

  @Override
  public AgentRuntimeCatalog getRuntime() {
    return this.mapper.toDomain(this.clientCallExecutor.execute(this.httpClient::getRuntime));
  }

  @Override
  public List<AgentDefinitionListItem> listProjectAgents(final UUID projectId) {
    final var response =
        this.clientCallExecutor.execute(() -> this.httpClient.listProjectAgents(projectId));
    return response == null ? null : response.stream().map(this.mapper::toDomain).toList();
  }

  @Override
  public AgentDefinitionDetails createAgent(
      final UUID projectId, final SaveAgentDefinitionCommand command) {
    final AgentDefinitionRequest request = this.mapper.toRequest(command);
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(() -> this.httpClient.createAgent(projectId, request)));
  }

  @Override
  public AgentDefinitionDetails getAgent(final UUID agentId) {
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(() -> this.httpClient.getAgent(agentId)));
  }

  @Override
  public AgentDefinitionDetails updateAgent(
      final UUID agentId, final SaveAgentDefinitionCommand command) {
    final AgentDefinitionRequest request = this.mapper.toRequest(command);
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(() -> this.httpClient.updateAgent(agentId, request)));
  }

  @Override
  public void deleteAgent(final UUID agentId) {
    this.clientCallExecutor.execute(
        () -> {
          this.httpClient.deleteAgent(agentId);
          return null;
        });
  }

  @Override
  public List<AgentWorkflow> listProjectWorkflows(final UUID projectId) {
    final var response =
        this.clientCallExecutor.execute(() -> this.httpClient.listProjectWorkflows(projectId));
    return response == null ? null : response.stream().map(this.mapper::toDomain).toList();
  }

  @Override
  public AgentWorkflow createWorkflow(
      final UUID projectId, final CreateAgentWorkflowCommand command) {
    final AgentWorkflowRequest request = this.mapper.toRequest(command);
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(() -> this.httpClient.createWorkflow(projectId, request)));
  }

  @Override
  public AgentWorkflow getWorkflow(final UUID workflowId) {
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(() -> this.httpClient.getWorkflow(workflowId)));
  }

  @Override
  public AgentWorkflow updateWorkflow(
      final UUID workflowId, final SaveAgentWorkflowCommand command) {
    final SaveAgentWorkflowRequest request = this.mapper.toRequest(command);
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(() -> this.httpClient.updateWorkflow(workflowId, request)));
  }

  @Override
  public void deleteWorkflow(final UUID workflowId) {
    this.clientCallExecutor.execute(
        () -> {
          this.httpClient.deleteWorkflow(workflowId);
          return null;
        });
  }

  @Override
  public AgentWorkflowRun createWorkflowRun(
      final UUID workflowId, final CreateAgentWorkflowRunCommand command) {
    final CreateWorkflowRunRequest request = this.mapper.toRequest(command);
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(
            () -> this.httpClient.createWorkflowRun(workflowId, request)));
  }

  @Override
  public List<AgentWorkflowRunSummary> listWorkflowRuns(final UUID workflowId) {
    final var response =
        this.clientCallExecutor.execute(() -> this.httpClient.listWorkflowRuns(workflowId));
    return response == null ? null : response.stream().map(this.mapper::toDomain).toList();
  }

  @Override
  public AgentWorkflowRun getWorkflowRun(final UUID runId) {
    return this.mapper.toDomain(
        this.clientCallExecutor.execute(() -> this.httpClient.getWorkflowRun(runId)));
  }

  @Override
  public List<AgentLogSource> listProjectLogSources(final UUID projectId) {
    final var response =
        clientCallExecutor.execute(() -> httpClient.listProjectLogSources(projectId));
    return response.stream().map(mapper::toDomain).toList();
  }

  @Override
  public AgentLogSource createProjectLogSource(
      final UUID projectId, final SaveAgentLogSourceCommand command) {
    final AgentLogSourceRequest request = mapper.toRequest(command);
    return mapper.toDomain(
        clientCallExecutor.execute(() -> httpClient.createProjectLogSource(projectId, request)));
  }

  @Override
  public AgentLogSource updateProjectLogSource(
      final UUID projectId, final UUID sourceId, final SaveAgentLogSourceCommand command) {
    final AgentLogSourceRequest request = mapper.toRequest(command);
    return mapper.toDomain(
        clientCallExecutor.execute(
            () -> httpClient.updateProjectLogSource(projectId, sourceId, request)));
  }

  @Override
  public void deleteProjectLogSource(final UUID projectId, final UUID sourceId) {
    clientCallExecutor.execute(
        () -> {
          httpClient.deleteProjectLogSource(projectId, sourceId);
          return null;
        });
  }

  @Override
  public List<AgentLogTargetCandidate> discoverProjectLogTargets(
      final UUID projectId, final AgentLogDiscoveryCommand command) {
    final AgentLogDiscoveryRequest request = mapper.toRequest(command);
    return clientCallExecutor
        .execute(() -> httpClient.discoverProjectLogTargets(projectId, request))
        .stream()
        .map(mapper::toDomain)
        .toList();
  }

  @Override
  public void validateProjectLogSource(
      final UUID projectId, final SaveAgentLogSourceCommand command) {
    final AgentLogSourceRequest request = mapper.toRequest(command);
    clientCallExecutor.execute(
        () -> {
          httpClient.validateProjectLogSource(projectId, request);
          return null;
        });
  }

  @Override
  public List<AgentSshConnection> listProjectSshConnections(final UUID projectId) {
    return clientCallExecutor
        .execute(() -> httpClient.listProjectSshConnections(projectId))
        .stream()
        .map(mapper::toDomain)
        .toList();
  }

  @Override
  public AgentSshConnection createProjectSshConnection(
      final UUID projectId, final CreateAgentSshConnectionCommand command) {
    final AgentSshConnectionRequest request = mapper.toRequest(command);
    return mapper.toDomain(
        clientCallExecutor.execute(
            () -> httpClient.createProjectSshConnection(projectId, request)));
  }

  @Override
  public void streamProjectLogs(
      final UUID projectId,
      final List<UUID> sourceIds,
      final int lines,
      final OutputStream output) {
    clientCallExecutor.execute(
        () -> {
          logStreamingHttpClient.stream(projectId, sourceIds, lines, output);
          return null;
        });
  }
}
