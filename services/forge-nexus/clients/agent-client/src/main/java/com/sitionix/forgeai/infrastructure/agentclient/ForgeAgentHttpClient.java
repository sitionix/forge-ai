package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentRuntimeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateProjectTaskRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ImportProjectRepositoryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskPageResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskSummaryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.SaveAgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunSummaryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogDiscoveryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogSourceRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogSourceResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogTargetCandidateResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentSshConnectionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentSshConnectionResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;
import org.springframework.web.bind.annotation.RequestBody;

public interface ForgeAgentHttpClient {

    @GetExchange("/api/v1/projects")
    List<AgentProjectResponse> listProjects();

    @PostExchange(value = "/api/v1/projects", contentType = MediaType.APPLICATION_JSON_VALUE)
    AgentProjectResponse createProject(@RequestBody AgentProjectRequest request);

    @DeleteExchange("/api/v1/projects/{projectId}")
    void deleteProject(@PathVariable UUID projectId);

    @PostExchange(value = "/api/v1/projects/{projectId}/repositories", contentType = MediaType.APPLICATION_JSON_VALUE)
    ProjectRepositoryResponse importProjectRepository(@PathVariable UUID projectId, @RequestBody ImportProjectRepositoryRequest request);

    @GetExchange("/api/v1/projects/{projectId}/repositories")
    List<ProjectRepositoryResponse> listProjectRepositories(@PathVariable UUID projectId);

    @PostExchange("/api/v1/projects/{projectId}/repositories/{repositoryId}/clone")
    ProjectRepositoryResponse cloneProjectRepository(@PathVariable UUID projectId, @PathVariable UUID repositoryId);

    @PostExchange("/api/v1/projects/{projectId}/repositories/{repositoryId}/refresh")
    ProjectRepositoryResponse refreshProjectRepository(@PathVariable UUID projectId, @PathVariable UUID repositoryId);

    @PostExchange("/api/v1/projects/{projectId}/repositories/{repositoryId}/pull")
    ProjectRepositoryResponse pullProjectRepository(@PathVariable UUID projectId, @PathVariable UUID repositoryId);

    @PostExchange(value = "/api/v1/projects/{projectId}/tasks", contentType = MediaType.APPLICATION_JSON_VALUE)
    ProjectTaskResponse createProjectTask(@PathVariable UUID projectId, @RequestBody CreateProjectTaskRequest request);

    @GetExchange("/api/v1/projects/{projectId}/tasks")
    ProjectTaskPageResponse listProjectTasks(@PathVariable UUID projectId, @RequestParam int page, @RequestParam int size);

    @GetExchange("/api/v1/tasks/{taskId}")
    ProjectTaskResponse getProjectTask(@PathVariable UUID taskId);

    @DeleteExchange("/api/v1/tasks/{taskId}")
    void deleteProjectTask(@PathVariable UUID taskId);

    @GetExchange("/api/v1/runtime")
    AgentRuntimeResponse getRuntime();

    @GetExchange("/api/v1/projects/{projectId}/agents")
    List<AgentDefinitionListResponse> listProjectAgents(@PathVariable UUID projectId);

    @PostExchange(value = "/api/v1/projects/{projectId}/agents", contentType = MediaType.APPLICATION_JSON_VALUE)
    AgentDefinitionResponse createAgent(@PathVariable UUID projectId, @RequestBody AgentDefinitionRequest request);

    @GetExchange("/api/v1/agents/{agentId}")
    AgentDefinitionResponse getAgent(@PathVariable UUID agentId);

    @PutExchange(value = "/api/v1/agents/{agentId}", contentType = MediaType.APPLICATION_JSON_VALUE)
    AgentDefinitionResponse updateAgent(@PathVariable UUID agentId, @RequestBody AgentDefinitionRequest request);

    @DeleteExchange("/api/v1/agents/{agentId}")
    void deleteAgent(@PathVariable UUID agentId);

    @GetExchange("/api/v1/projects/{projectId}/workflows")
    List<AgentWorkflowResponse> listProjectWorkflows(@PathVariable UUID projectId);

    @PostExchange(value = "/api/v1/projects/{projectId}/workflows", contentType = MediaType.APPLICATION_JSON_VALUE)
    AgentWorkflowResponse createWorkflow(@PathVariable UUID projectId, @RequestBody AgentWorkflowRequest request);

    @GetExchange("/api/v1/workflows/{workflowId}")
    AgentWorkflowResponse getWorkflow(@PathVariable UUID workflowId);

    @PutExchange(value = "/api/v1/workflows/{workflowId}", contentType = MediaType.APPLICATION_JSON_VALUE)
    AgentWorkflowResponse updateWorkflow(@PathVariable UUID workflowId, @RequestBody SaveAgentWorkflowRequest request);

    @DeleteExchange("/api/v1/workflows/{workflowId}")
    void deleteWorkflow(@PathVariable UUID workflowId);

    @PostExchange(value = "/api/v1/workflows/{workflowId}/runs", contentType = MediaType.APPLICATION_JSON_VALUE)
    WorkflowRunResponse createWorkflowRun(@PathVariable UUID workflowId, @RequestBody CreateWorkflowRunRequest request);

    @GetExchange("/api/v1/workflows/{workflowId}/runs")
    List<WorkflowRunSummaryResponse> listWorkflowRuns(@PathVariable UUID workflowId);

    @GetExchange("/api/v1/workflow-runs/{runId}")
    WorkflowRunResponse getWorkflowRun(@PathVariable UUID runId);

    @GetExchange("/api/v1/projects/{projectId}/log-sources")
    List<AgentLogSourceResponse> listProjectLogSources(@PathVariable UUID projectId);

    @PostExchange(
        value = "/api/v1/projects/{projectId}/log-sources",
        contentType = MediaType.APPLICATION_JSON_VALUE)
    AgentLogSourceResponse createProjectLogSource(
        @PathVariable UUID projectId, @RequestBody AgentLogSourceRequest request);

    @PutExchange(
        value = "/api/v1/projects/{projectId}/log-sources/{sourceId}",
        contentType = MediaType.APPLICATION_JSON_VALUE)
    AgentLogSourceResponse updateProjectLogSource(
        @PathVariable UUID projectId,
        @PathVariable UUID sourceId,
        @RequestBody AgentLogSourceRequest request);

    @DeleteExchange("/api/v1/projects/{projectId}/log-sources/{sourceId}")
    void deleteProjectLogSource(@PathVariable UUID projectId, @PathVariable UUID sourceId);

    @PostExchange(
        value = "/api/v1/projects/{projectId}/log-sources/discover",
        contentType = MediaType.APPLICATION_JSON_VALUE)
    List<AgentLogTargetCandidateResponse> discoverProjectLogTargets(
        @PathVariable UUID projectId, @RequestBody AgentLogDiscoveryRequest request);

    @PostExchange(
        value = "/api/v1/projects/{projectId}/log-sources/validate",
        contentType = MediaType.APPLICATION_JSON_VALUE)
    void validateProjectLogSource(
        @PathVariable UUID projectId, @RequestBody AgentLogSourceRequest request);

    @GetExchange("/api/v1/projects/{projectId}/ssh-connections")
    List<AgentSshConnectionResponse> listProjectSshConnections(@PathVariable UUID projectId);

    @PostExchange(
        value = "/api/v1/projects/{projectId}/ssh-connections",
        contentType = MediaType.APPLICATION_JSON_VALUE)
    AgentSshConnectionResponse createProjectSshConnection(
        @PathVariable UUID projectId, @RequestBody AgentSshConnectionRequest request);
}
