package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.domain.model.agentproxy.AgentServiceMetricsSnapshot;
import com.sitionix.forgeai.domain.model.agentproxy.AgentServiceResourceMetrics;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskPage;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskSummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetCandidate;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetDiscoveryCommand;
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
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogDiscoveryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogSource;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogTargetCandidate;
import com.sitionix.forgeai.domain.model.agentproxy.AgentSshConnection;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentSshConnectionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentLogSourceCommand;
import com.sitionix.forgeai.domain.port.AgentLogStream;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogDiscoveryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogSourceRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentSshConnectionRequest;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateProjectTaskRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ImportProjectRepositoryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RuntimeTargetDiscoveryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.SaveAgentWorkflowRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ForgeAgentClientAdapter implements ForgeAgentClient {

    @Override
    public com.sitionix.forgeai.domain.model.agentproxy.AgentProjectAsset getProjectAsset(
            final UUID projectId, final UUID assetId) {
        return this.asset(this.clientCallExecutor.execute(
                () -> this.httpClient.getProjectAsset(projectId, assetId)));
    }

    @Override
    public List<com.sitionix.forgeai.domain.model.agentproxy.AgentProjectAsset> listProjectAssets(
            final UUID projectId) {
        return this.clientCallExecutor.execute(() -> this.httpClient.listProjectAssets(projectId)).stream()
                .map(this::asset)
                .toList();
    }

    @Override
    public com.sitionix.forgeai.domain.model.agentproxy.AgentProjectAsset createProjectAsset(
            final UUID projectId,
            final com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectAssetCommand command) {
        final var request = new com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectAssetRequest(
                command.name(), command.sshConnectionId());
        return this.asset(this.clientCallExecutor.execute(
                () -> this.httpClient.createProjectAsset(projectId, request)));
    }

    @Override
    public com.sitionix.forgeai.domain.model.agentproxy.AgentAssetCapabilities getProjectAssetCapabilities(
            final UUID projectId, final UUID assetId) {
        final var response = this.clientCallExecutor.execute(
                () -> this.httpClient.getProjectAssetCapabilities(projectId, assetId));
        return new com.sitionix.forgeai.domain.model.agentproxy.AgentAssetCapabilities(
                response.systemdAvailable(), response.dockerAvailable());
    }

    @Override
    public com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics getProjectAssetMetrics(
            final UUID projectId, final UUID assetId) {
        final var response = this.clientCallExecutor.execute(
                () -> this.httpClient.getProjectAssetMetrics(projectId, assetId));
        return new com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics(
                response.cpuTotalPercent(), response.cpuPerCorePercent(),
                response.ramTotalBytes(), response.ramUsedBytes(),
                response.loadAverage1m(), response.loadAverage5m(), response.loadAverage15m(),
                response.disks().stream()
                        .map(disk -> new com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics.Disk(
                                disk.mount(), disk.totalBytes(), disk.usedBytes()))
                        .toList(),
                response.network().stream()
                        .map(network -> new com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics.Network(
                                network.interfaceName(), network.receivedBytes(), network.transmittedBytes()))
                        .toList(),
                response.uptimeSeconds(),
                response.temperatures().stream()
                        .map(temperature -> new com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics.Temperature(
                                temperature.sensor(), temperature.celsius()))
                        .toList());
    }

    @Override
    public void deleteProjectAsset(final UUID projectId, final UUID assetId) {
        this.clientCallExecutor.execute(() -> {
            this.httpClient.deleteProjectAsset(projectId, assetId);
            return null;
        });
    }

    @Override
    public List<AgentLogSource> listProjectAssetMonitoring(final UUID projectId, final UUID assetId) {
        return this.clientCallExecutor.execute(
                        () -> this.httpClient.listProjectAssetMonitoring(projectId, assetId)).stream()
                .map(this.mapper::toDomain)
                .toList();
    }

    @Override
    public AgentLogSource createProjectAssetMonitoring(
            final UUID projectId,
            final UUID assetId,
            final com.sitionix.forgeai.domain.model.agentproxy.SaveAgentAssetMonitoringCommand command) {
        final var request = new com.sitionix.forgeai.infrastructure.agentclient.dto.AssetMonitoringRequest(
                command.name(), command.provider(), command.target(), command.enabled());
        return this.mapper.toDomain(this.clientCallExecutor.execute(
                () -> this.httpClient.createProjectAssetMonitoring(projectId, assetId, request)));
    }

    @Override
    public List<AgentLogSource> replaceProjectAssetMonitoring(final UUID projectId, final UUID assetId,
            final com.sitionix.forgeai.domain.model.agentproxy.ReplaceAgentAssetMonitoringCommand command) {
        var request = new com.sitionix.forgeai.infrastructure.agentclient.dto.AssetMonitoringReplacementRequest(
                command.targets().stream().map(target ->
                        new com.sitionix.forgeai.infrastructure.agentclient.dto.AssetMonitoringReplacementRequest.Target(
                                target.provider(), target.target())).toList());
        return this.clientCallExecutor.execute(() ->
                this.httpClient.replaceProjectAssetMonitoring(projectId, assetId, request)).stream()
                .map(this.mapper::toDomain).toList();
    }

    private com.sitionix.forgeai.domain.model.agentproxy.AgentProjectAsset asset(
            final com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectAssetResponse response) {
        return new com.sitionix.forgeai.domain.model.agentproxy.AgentProjectAsset(
                response.id(), response.projectId(), response.name(), response.sshConnectionId(),
                response.createdAt(), response.updatedAt());
    }

    public java.util.List<com.sitionix.forgeai.domain.model.agentproxy.AgentProjectService> listProjectServices(UUID p){return clientCallExecutor.execute(()->httpClient.listProjectServices(p)).stream().map(mapper::toDomain).toList();}
    public com.sitionix.forgeai.domain.model.agentproxy.AgentProjectService createProjectService(UUID p,com.sitionix.forgeai.domain.model.agentproxy.SaveAgentProjectServiceCommand c){return mapper.toDomain(clientCallExecutor.execute(()->httpClient.createProjectService(p,mapper.toRequest(c))));}
    public com.sitionix.forgeai.domain.model.agentproxy.AgentProjectService getProjectService(UUID p,UUID s){return mapper.toDomain(clientCallExecutor.execute(()->httpClient.getProjectService(p,s)));}
    public com.sitionix.forgeai.domain.model.agentproxy.AgentProjectService updateProjectService(UUID p,UUID s,com.sitionix.forgeai.domain.model.agentproxy.SaveAgentProjectServiceCommand c){return mapper.toDomain(clientCallExecutor.execute(()->httpClient.updateProjectService(p,s,mapper.toRequest(c))));}
    public void deleteProjectService(UUID p,UUID s){clientCallExecutor.execute(()->{httpClient.deleteProjectService(p,s);return null;});}
    public com.sitionix.forgeai.domain.model.agentproxy.AgentServiceRuntime getProjectServiceRuntime(UUID p,UUID s){return mapper.toDomain(clientCallExecutor.execute(()->httpClient.getProjectServiceRuntime(p,s)));}
    public java.util.List<com.sitionix.forgeai.domain.model.agentproxy.AgentLogSource> listProjectServiceLogSources(UUID p,UUID s){return clientCallExecutor.execute(()->httpClient.listProjectServiceLogSources(p,s)).stream().map(mapper::toDomain).toList();}
    public List<AgentRuntimeTargetCandidate> discoverProjectRuntimeTargets(UUID p,AgentRuntimeTargetDiscoveryCommand c){RuntimeTargetDiscoveryRequest r=mapper.toRequest(c);return clientCallExecutor.execute(()->httpClient.discoverProjectRuntimeTargets(p,r)).stream().map(mapper::toDomain).toList();}

    private final ForgeAgentHttpClient httpClient;
    private final ForgeAgentClientMapper mapper;
    private final ForgeAgentClientCallExecutor clientCallExecutor;
    private final ForgeAgentLogStreamingHttpClient logStreamingHttpClient;

    @Override
    public List<AgentProject> listProjects() {
        final var response = this.clientCallExecutor.execute(this.httpClient::listProjects);
        return response == null ? null : response.stream()
                .map(this.mapper::toDomain)
                .toList();
    }

    @Override
    public AgentProject createProject(final CreateAgentProjectCommand command) {
        final AgentProjectRequest request = this.mapper.toRequest(command);
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.createProject(request)));
    }

    @Override
    public void deleteProject(final UUID projectId) {
        this.clientCallExecutor.execute(() -> {
            this.httpClient.deleteProject(projectId);
            return null;
        });
    }

    @Override
    public AgentProjectRepository importProjectRepository(final UUID projectId, final ImportAgentProjectRepositoryCommand command) {
        final ImportProjectRepositoryRequest request = this.mapper.toRequest(command);
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.importProjectRepository(projectId, request)));
    }

    @Override
    public List<AgentProjectRepository> listProjectRepositories(final UUID projectId) {
        final var response = this.clientCallExecutor.execute(() -> this.httpClient.listProjectRepositories(projectId));
        return response == null ? null : response.stream()
                .map(this.mapper::toDomain)
                .toList();
    }

    @Override
    public AgentProjectRepository cloneProjectRepository(final UUID projectId, final UUID repositoryId) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.cloneProjectRepository(projectId, repositoryId)));
    }

    @Override
    public AgentProjectRepository refreshProjectRepository(final UUID projectId, final UUID repositoryId) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.refreshProjectRepository(projectId, repositoryId)));
    }

    @Override
    public AgentProjectRepository pullProjectRepository(final UUID projectId, final UUID repositoryId) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.pullProjectRepository(projectId, repositoryId)));
    }

    @Override
    public AgentProjectTask createProjectTask(final UUID projectId, final CreateAgentProjectTaskCommand command) {
        final CreateProjectTaskRequest request = this.mapper.toRequest(command);
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.createProjectTask(projectId, request)));
    }

    @Override
    public AgentProjectTaskPage listProjectTasks(final UUID projectId, final int page, final int size) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.listProjectTasks(projectId, page, size)));
    }

    @Override
    public AgentProjectTask getProjectTask(final UUID taskId) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.getProjectTask(taskId)));
    }

    @Override
    public void deleteProjectTask(final UUID taskId) {
        this.clientCallExecutor.execute(() -> {
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
        final var response = this.clientCallExecutor.execute(() -> this.httpClient.listProjectAgents(projectId));
        return response == null ? null : response.stream()
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
    public void deleteAgent(final UUID agentId) {
        this.clientCallExecutor.execute(() -> {
            this.httpClient.deleteAgent(agentId);
            return null;
        });
    }

    @Override
    public List<AgentWorkflow> listProjectWorkflows(final UUID projectId) {
        final var response = this.clientCallExecutor.execute(() -> this.httpClient.listProjectWorkflows(projectId));
        return response == null ? null : response.stream()
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
    public void deleteWorkflow(final UUID workflowId) {
        this.clientCallExecutor.execute(() -> {
            this.httpClient.deleteWorkflow(workflowId);
            return null;
        });
    }

    @Override
    public AgentWorkflowRun createWorkflowRun(final UUID workflowId, final CreateAgentWorkflowRunCommand command) {
        final CreateWorkflowRunRequest request = this.mapper.toRequest(command);
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.createWorkflowRun(workflowId, request)));
    }

    @Override
    public List<AgentWorkflowRunSummary> listWorkflowRuns(final UUID workflowId) {
        final var response = this.clientCallExecutor.execute(() -> this.httpClient.listWorkflowRuns(workflowId));
        return response == null ? null : response.stream()
                .map(this.mapper::toDomain)
                .toList();
    }

    @Override
    public AgentWorkflowRun getWorkflowRun(final UUID runId) {
        return this.mapper.toDomain(this.clientCallExecutor.execute(() -> this.httpClient.getWorkflowRun(runId)));
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
    public void testProjectSshConnection(
        final UUID projectId, final CreateAgentSshConnectionCommand command) {
      final AgentSshConnectionRequest request = mapper.toRequest(command);
      clientCallExecutor.execute(
          () -> {
            httpClient.testProjectSshConnection(projectId, request);
            return null;
          });
    }

    @Override
    public com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics getProjectSshConnectionMetrics(
            final UUID projectId, final UUID connectionId) {
        return assetMetrics(this.clientCallExecutor.execute(
                () -> this.httpClient.getProjectSshConnectionMetrics(projectId, connectionId)));
    }

    @Override
    public AgentServiceMetricsSnapshot getProjectSshConnectionServiceMetrics(UUID projectId, UUID connectionId) {
        var response = clientCallExecutor.execute(
            () -> httpClient.getProjectSshConnectionServiceMetrics(projectId, connectionId));
        return new AgentServiceMetricsSnapshot(response.sampledAt(), response.services().stream()
            .map(s -> new AgentServiceResourceMetrics(s.unit(), s.description(), s.cpuUsageNanos(), s.memoryBytes(), s.tasks()))
            .toList());
    }

    @Override
    public com.sitionix.forgeai.domain.model.agentproxy.AgentServiceProcessMetricsSnapshot
        getProjectSshConnectionServiceProcesses(UUID projectId, UUID connectionId, String unit, String sort) {
        var response = clientCallExecutor.execute(
            () -> httpClient.getProjectSshConnectionServiceProcesses(projectId, connectionId, unit, sort));
        return new com.sitionix.forgeai.domain.model.agentproxy.AgentServiceProcessMetricsSnapshot(
            response.unit(), response.sort(), response.sampledAt(), response.processes().stream()
                .map(p -> new com.sitionix.forgeai.domain.model.agentproxy.AgentServiceProcessMetrics(
                    p.pid(), p.process(), p.cpuPercent(), p.rssBytes(), p.threads())).toList());
    }

    private com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics assetMetrics(
            com.sitionix.forgeai.infrastructure.agentclient.dto.AssetMetricsResponse response) {
        return new com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics(
                response.cpuTotalPercent(), response.cpuPerCorePercent(), response.ramTotalBytes(), response.ramUsedBytes(),
                response.loadAverage1m(), response.loadAverage5m(), response.loadAverage15m(),
                response.disks().stream().map(d -> new com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics.Disk(d.mount(), d.totalBytes(), d.usedBytes())).toList(),
                response.network().stream().map(n -> new com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics.Network(n.interfaceName(), n.receivedBytes(), n.transmittedBytes())).toList(),
                response.uptimeSeconds(), response.temperatures().stream().map(t -> new com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics.Temperature(t.sensor(), t.celsius())).toList());
    }

    @Override
    public AgentLogStream openProjectLogsStream(
        final UUID projectId, final List<UUID> sourceIds, final int lines) {
      return clientCallExecutor.execute(
          () -> logStreamingHttpClient.open(projectId, sourceIds, lines));
    }
}
