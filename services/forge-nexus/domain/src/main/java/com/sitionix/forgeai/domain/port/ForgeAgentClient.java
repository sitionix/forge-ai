package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogDiscoveryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogSource;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogTargetCandidate;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskPage;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetCandidate;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetDiscoveryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.AgentSshConnection;
import com.sitionix.forgeai.domain.model.agentproxy.AgentServiceMetricsSnapshot;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentSshConnectionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.ImportAgentProjectRepositoryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentLogSourceCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import java.util.List;
import java.util.UUID;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectService;
import com.sitionix.forgeai.domain.model.agentproxy.AgentServiceRuntime;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentProjectServiceCommand;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectAsset;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectAssetCommand;
import com.sitionix.forgeai.domain.model.agentproxy.AgentAssetMetrics;
import com.sitionix.forgeai.domain.model.agentproxy.AgentAssetCapabilities;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentAssetMonitoringCommand;
import com.sitionix.forgeai.domain.model.agentproxy.ReplaceAgentAssetMonitoringCommand;

public interface ForgeAgentClient {
  List<AgentProjectAsset> listProjectAssets(UUID projectId);
  AgentProjectAsset createProjectAsset(UUID projectId, CreateAgentProjectAssetCommand command);
  AgentProjectAsset getProjectAsset(UUID projectId, UUID assetId);
  AgentAssetMetrics getProjectAssetMetrics(UUID projectId, UUID assetId);
  AgentAssetCapabilities getProjectAssetCapabilities(UUID projectId, UUID assetId);
  void deleteProjectAsset(UUID projectId, UUID assetId);
  List<AgentLogSource> listProjectAssetMonitoring(UUID projectId, UUID assetId);
  AgentLogSource createProjectAssetMonitoring(UUID projectId, UUID assetId, SaveAgentAssetMonitoringCommand command);
  List<AgentLogSource> replaceProjectAssetMonitoring(UUID projectId, UUID assetId, ReplaceAgentAssetMonitoringCommand command);
  List<AgentProjectService> listProjectServices(UUID projectId);
  AgentProjectService createProjectService(UUID projectId, SaveAgentProjectServiceCommand command);
  AgentProjectService getProjectService(UUID projectId, UUID serviceId);
  AgentProjectService updateProjectService(UUID projectId, UUID serviceId, SaveAgentProjectServiceCommand command);
  void deleteProjectService(UUID projectId, UUID serviceId);
  AgentServiceRuntime getProjectServiceRuntime(UUID projectId, UUID serviceId);
  List<AgentLogSource> listProjectServiceLogSources(UUID projectId, UUID serviceId);

  List<AgentRuntimeTargetCandidate> discoverProjectRuntimeTargets(
      UUID projectId, AgentRuntimeTargetDiscoveryCommand command);

  List<AgentProject> listProjects();

  AgentProject createProject(CreateAgentProjectCommand command);

  void deleteProject(UUID projectId);

  AgentProjectRepository importProjectRepository(
      UUID projectId, ImportAgentProjectRepositoryCommand command);

  List<AgentProjectRepository> listProjectRepositories(UUID projectId);

  AgentProjectRepository cloneProjectRepository(UUID projectId, UUID repositoryId);

  AgentProjectRepository refreshProjectRepository(UUID projectId, UUID repositoryId);

  AgentProjectRepository pullProjectRepository(UUID projectId, UUID repositoryId);

  AgentProjectTask createProjectTask(UUID projectId, CreateAgentProjectTaskCommand command);

  AgentProjectTaskPage listProjectTasks(UUID projectId, int page, int size);

  AgentProjectTask getProjectTask(UUID taskId);

  void deleteProjectTask(UUID taskId);

  AgentRuntimeCatalog getRuntime();

  List<AgentDefinitionListItem> listProjectAgents(UUID projectId);

  AgentDefinitionDetails createAgent(UUID projectId, SaveAgentDefinitionCommand command);

  AgentDefinitionDetails getAgent(UUID agentId);

  AgentDefinitionDetails updateAgent(UUID agentId, SaveAgentDefinitionCommand command);

  void deleteAgent(UUID agentId);

  List<AgentWorkflow> listProjectWorkflows(UUID projectId);

  AgentWorkflow createWorkflow(UUID projectId, CreateAgentWorkflowCommand command);

  AgentWorkflow getWorkflow(UUID workflowId);

  AgentWorkflow updateWorkflow(UUID workflowId, SaveAgentWorkflowCommand command);

  void deleteWorkflow(UUID workflowId);

  AgentWorkflowRun createWorkflowRun(UUID workflowId, CreateAgentWorkflowRunCommand command);

  List<AgentWorkflowRunSummary> listWorkflowRuns(UUID workflowId);

  AgentWorkflowRun getWorkflowRun(UUID runId);

  List<AgentLogSource> listProjectLogSources(UUID projectId);

  AgentLogSource createProjectLogSource(UUID projectId, SaveAgentLogSourceCommand command);

  AgentLogSource updateProjectLogSource(
      UUID projectId, UUID sourceId, SaveAgentLogSourceCommand command);

  void deleteProjectLogSource(UUID projectId, UUID sourceId);

  List<AgentLogTargetCandidate> discoverProjectLogTargets(
      UUID projectId, AgentLogDiscoveryCommand command);

  void validateProjectLogSource(UUID projectId, SaveAgentLogSourceCommand command);

  List<AgentSshConnection> listProjectSshConnections(UUID projectId);

  AgentSshConnection createProjectSshConnection(
      UUID projectId, CreateAgentSshConnectionCommand command);

  void testProjectSshConnection(UUID projectId, CreateAgentSshConnectionCommand command);

  AgentAssetMetrics getProjectSshConnectionMetrics(UUID projectId, UUID connectionId);
  AgentServiceMetricsSnapshot getProjectSshConnectionServiceMetrics(UUID projectId, UUID connectionId);
  com.sitionix.forgeai.domain.model.agentproxy.AgentServiceProcessMetricsSnapshot
      getProjectSshConnectionServiceProcesses(UUID projectId, UUID connectionId, String unit, String sort);

  AgentLogStream openProjectLogsStream(UUID projectId, List<UUID> sourceIds, int lines);
}
