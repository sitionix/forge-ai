package com.sitionix.forgeagent.it.infra;

import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.AiRuntimeResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.CreateProjectTaskRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRequest;
import com.sitionix.forgeagent.api.dto.ForgeAgentErrorResponse;
import com.sitionix.forgeagent.api.dto.ImportProjectRepositoryRequest;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.ProjectServiceRequest;
import com.sitionix.forgeagent.api.dto.ProjectServiceResponse;
import com.sitionix.forgeagent.api.dto.ProjectAssetRequest;
import com.sitionix.forgeagent.api.dto.ProjectAssetResponse;
import com.sitionix.forgeagent.api.dto.AssetMonitoringRequest;
import com.sitionix.forgeagent.domain.model.AssetCapabilities;
import com.sitionix.forgeagent.domain.model.AssetMetrics;
import com.sitionix.forgeagent.api.dto.ServiceRuntimeResponse;
import com.sitionix.forgeagent.api.dto.LogSourceRequest;
import com.sitionix.forgeagent.api.dto.LogSourceResponse;
import com.sitionix.forgeagent.api.dto.ProjectRepositoryResponse;
import com.sitionix.forgeagent.api.dto.ProjectTaskPageResponse;
import com.sitionix.forgeagent.api.dto.ProjectTaskResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.api.dto.SaveWorkflowRequest;
import com.sitionix.forgeagent.api.dto.WorkflowRunResponse;
import com.sitionix.forgeagent.api.dto.WorkflowRunSummaryResponse;
import com.sitionix.forgeagent.api.dto.WorkflowResponse;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;

public final class ForgeAgentMockMvcEndpoint {

    public static final Endpoint<ProjectAssetRequest, ProjectAssetResponse> CREATE_PROJECT_ASSET =
            Endpoint.createContract("/api/v1/projects/{projectId}/assets", HttpMethod.POST, ProjectAssetRequest.class, ProjectAssetResponse.class);
    public static final Endpoint<ProjectAssetRequest, ForgeAgentErrorResponse> CREATE_PROJECT_ASSET_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/assets", HttpMethod.POST, ProjectAssetRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, ProjectAssetResponse[]> LIST_PROJECT_ASSETS =
            Endpoint.createContract("/api/v1/projects/{projectId}/assets", HttpMethod.GET, Void.class, ProjectAssetResponse[].class);
    public static final Endpoint<Void, ProjectAssetResponse> GET_PROJECT_ASSET =
            Endpoint.createContract("/api/v1/projects/{projectId}/assets/{assetId}", HttpMethod.GET, Void.class, ProjectAssetResponse.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> GET_PROJECT_ASSET_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/assets/{assetId}", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, AssetMetrics> GET_PROJECT_ASSET_METRICS =
            Endpoint.createContract("/api/v1/projects/{projectId}/assets/{assetId}/metrics", HttpMethod.GET, Void.class, AssetMetrics.class);
    public static final Endpoint<Void, AssetCapabilities> GET_PROJECT_ASSET_CAPABILITIES =
            Endpoint.createContract("/api/v1/projects/{projectId}/assets/{assetId}/capabilities", HttpMethod.GET, Void.class, AssetCapabilities.class);
    public static final Endpoint<AssetMonitoringRequest, LogSourceResponse> CREATE_PROJECT_ASSET_MONITORING =
            Endpoint.createContract("/api/v1/projects/{projectId}/assets/{assetId}/monitoring", HttpMethod.POST, AssetMonitoringRequest.class, LogSourceResponse.class);
    public static final Endpoint<Void, LogSourceResponse[]> LIST_PROJECT_ASSET_MONITORING =
            Endpoint.createContract("/api/v1/projects/{projectId}/assets/{assetId}/monitoring", HttpMethod.GET, Void.class, LogSourceResponse[].class);
    public static final Endpoint<Void, Void> DELETE_PROJECT_ASSET =
            Endpoint.createContract("/api/v1/projects/{projectId}/assets/{assetId}", HttpMethod.DELETE, Void.class, Void.class);

    public static final Endpoint<Void, ProjectResponse[]> LIST_PROJECTS =
            Endpoint.createContract("/api/v1/projects", HttpMethod.GET, Void.class, ProjectResponse[].class);
    public static final Endpoint<ProjectServiceRequest, ProjectServiceResponse> CREATE_PROJECT_SERVICE =
            Endpoint.createContract("/api/v1/projects/{projectId}/services", HttpMethod.POST, ProjectServiceRequest.class, ProjectServiceResponse.class);
    public static final Endpoint<Void, ProjectServiceResponse[]> LIST_PROJECT_SERVICES =
            Endpoint.createContract("/api/v1/projects/{projectId}/services", HttpMethod.GET, Void.class, ProjectServiceResponse[].class);
    public static final Endpoint<Void, ProjectServiceResponse> GET_PROJECT_SERVICE =
            Endpoint.createContract("/api/v1/projects/{projectId}/services/{serviceId}", HttpMethod.GET, Void.class, ProjectServiceResponse.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> GET_PROJECT_SERVICE_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/services/{serviceId}", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<ProjectServiceRequest, ProjectServiceResponse> UPDATE_PROJECT_SERVICE =
            Endpoint.createContract("/api/v1/projects/{projectId}/services/{serviceId}", HttpMethod.PUT, ProjectServiceRequest.class, ProjectServiceResponse.class);
    public static final Endpoint<Void, Void> DELETE_PROJECT_SERVICE =
            Endpoint.createContract("/api/v1/projects/{projectId}/services/{serviceId}", HttpMethod.DELETE, Void.class, Void.class);
    public static final Endpoint<Void, ServiceRuntimeResponse> GET_PROJECT_SERVICE_RUNTIME =
            Endpoint.createContract("/api/v1/projects/{projectId}/services/{serviceId}/runtime", HttpMethod.GET, Void.class, ServiceRuntimeResponse.class);
    public static final Endpoint<LogSourceRequest, LogSourceResponse> CREATE_LOG_SOURCE =
            Endpoint.createContract("/api/v1/projects/{projectId}/log-sources", HttpMethod.POST, LogSourceRequest.class, LogSourceResponse.class);
    public static final Endpoint<Void, LogSourceResponse[]> LIST_SERVICE_LOG_SOURCES =
            Endpoint.createContract("/api/v1/projects/{projectId}/services/{serviceId}/log-sources", HttpMethod.GET, Void.class, LogSourceResponse[].class);
    public static final Endpoint<CreateProjectRequest, ProjectResponse> CREATE_PROJECT =
            Endpoint.createContract("/api/v1/projects", HttpMethod.POST, CreateProjectRequest.class, ProjectResponse.class);
    public static final Endpoint<CreateProjectRequest, ForgeAgentErrorResponse> CREATE_PROJECT_ERROR =
            Endpoint.createContract("/api/v1/projects", HttpMethod.POST, CreateProjectRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, Void> DELETE_PROJECT =
            Endpoint.createContract("/api/v1/projects/{projectId}", HttpMethod.DELETE, Void.class, Void.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> DELETE_PROJECT_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}", HttpMethod.DELETE, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<ImportProjectRepositoryRequest, ProjectRepositoryResponse> IMPORT_PROJECT_REPOSITORY =
            Endpoint.createContract("/api/v1/projects/{projectId}/repositories", HttpMethod.POST, ImportProjectRepositoryRequest.class, ProjectRepositoryResponse.class);
    public static final Endpoint<ImportProjectRepositoryRequest, ForgeAgentErrorResponse> IMPORT_PROJECT_REPOSITORY_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/repositories", HttpMethod.POST, ImportProjectRepositoryRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, ProjectRepositoryResponse[]> LIST_PROJECT_REPOSITORIES =
            Endpoint.createContract("/api/v1/projects/{projectId}/repositories", HttpMethod.GET, Void.class, ProjectRepositoryResponse[].class);
    public static final Endpoint<Void, ProjectRepositoryResponse> CLONE_PROJECT_REPOSITORY =
            Endpoint.createContract("/api/v1/projects/{projectId}/repositories/{repositoryId}/clone", HttpMethod.POST, Void.class, ProjectRepositoryResponse.class);
    public static final Endpoint<Void, ProjectRepositoryResponse> REFRESH_PROJECT_REPOSITORY =
            Endpoint.createContract("/api/v1/projects/{projectId}/repositories/{repositoryId}/refresh", HttpMethod.POST, Void.class, ProjectRepositoryResponse.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> REFRESH_PROJECT_REPOSITORY_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/repositories/{repositoryId}/refresh", HttpMethod.POST, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, ProjectRepositoryResponse> PULL_PROJECT_REPOSITORY =
            Endpoint.createContract("/api/v1/projects/{projectId}/repositories/{repositoryId}/pull", HttpMethod.POST, Void.class, ProjectRepositoryResponse.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> PULL_PROJECT_REPOSITORY_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/repositories/{repositoryId}/pull", HttpMethod.POST, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<CreateProjectTaskRequest, ProjectTaskResponse> CREATE_PROJECT_TASK =
            Endpoint.createContract("/api/v1/projects/{projectId}/tasks", HttpMethod.POST, CreateProjectTaskRequest.class, ProjectTaskResponse.class);
    public static final Endpoint<CreateProjectTaskRequest, ForgeAgentErrorResponse> CREATE_PROJECT_TASK_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/tasks", HttpMethod.POST, CreateProjectTaskRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, ProjectTaskPageResponse> LIST_PROJECT_TASKS =
            Endpoint.createContract("/api/v1/projects/{projectId}/tasks", HttpMethod.GET, Void.class, ProjectTaskPageResponse.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> LIST_PROJECT_TASKS_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/tasks", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, ProjectTaskResponse> GET_PROJECT_TASK =
            Endpoint.createContract("/api/v1/tasks/{taskId}", HttpMethod.GET, Void.class, ProjectTaskResponse.class);
    public static final Endpoint<Void, Void> DELETE_PROJECT_TASK =
            Endpoint.createContract("/api/v1/tasks/{taskId}", HttpMethod.DELETE, Void.class, Void.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> DELETE_PROJECT_TASK_ERROR =
            Endpoint.createContract("/api/v1/tasks/{taskId}", HttpMethod.DELETE, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, AiRuntimeResponse> GET_RUNTIME =
            Endpoint.createContract("/api/v1/runtime", HttpMethod.GET, Void.class, AiRuntimeResponse.class);
    public static final Endpoint<Void, AgentListResponse[]> LIST_PROJECT_AGENTS =
            Endpoint.createContract("/api/v1/projects/{projectId}/agents", HttpMethod.GET, Void.class, AgentListResponse[].class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> LIST_PROJECT_AGENTS_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/agents", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<SaveAgentRequest, AgentResponse> CREATE_AGENT =
            Endpoint.createContract("/api/v1/projects/{projectId}/agents", HttpMethod.POST, SaveAgentRequest.class, AgentResponse.class);
    public static final Endpoint<SaveAgentRequest, ForgeAgentErrorResponse> CREATE_AGENT_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/agents", HttpMethod.POST, SaveAgentRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, AgentResponse> GET_AGENT =
            Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.GET, Void.class, AgentResponse.class);
    public static final Endpoint<Void, Void> DELETE_AGENT =
            Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.DELETE, Void.class, Void.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> DELETE_AGENT_ERROR =
            Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.DELETE, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> GET_AGENT_ERROR =
            Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<SaveAgentRequest, AgentResponse> UPDATE_AGENT =
            Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.PUT, SaveAgentRequest.class, AgentResponse.class);
    public static final Endpoint<SaveAgentRequest, ForgeAgentErrorResponse> UPDATE_AGENT_ERROR =
            Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.PUT, SaveAgentRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, WorkflowResponse[]> LIST_PROJECT_WORKFLOWS =
            Endpoint.createContract("/api/v1/projects/{projectId}/workflows", HttpMethod.GET, Void.class, WorkflowResponse[].class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> LIST_PROJECT_WORKFLOWS_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/workflows", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<CreateWorkflowRequest, WorkflowResponse> CREATE_WORKFLOW =
            Endpoint.createContract("/api/v1/projects/{projectId}/workflows", HttpMethod.POST, CreateWorkflowRequest.class, WorkflowResponse.class);
    public static final Endpoint<CreateWorkflowRequest, ForgeAgentErrorResponse> CREATE_WORKFLOW_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/workflows", HttpMethod.POST, CreateWorkflowRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, WorkflowResponse> GET_WORKFLOW =
            Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.GET, Void.class, WorkflowResponse.class);
    public static final Endpoint<Void, Void> DELETE_WORKFLOW =
            Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.DELETE, Void.class, Void.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> DELETE_WORKFLOW_ERROR =
            Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.DELETE, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> GET_WORKFLOW_ERROR =
            Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<SaveWorkflowRequest, WorkflowResponse> UPDATE_WORKFLOW =
            Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.PUT, SaveWorkflowRequest.class, WorkflowResponse.class);
    public static final Endpoint<SaveWorkflowRequest, ForgeAgentErrorResponse> UPDATE_WORKFLOW_ERROR =
            Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.PUT, SaveWorkflowRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<CreateWorkflowRunRequest, WorkflowRunResponse> CREATE_WORKFLOW_RUN =
            Endpoint.createContract("/api/v1/workflows/{workflowId}/runs", HttpMethod.POST, CreateWorkflowRunRequest.class, WorkflowRunResponse.class);
    public static final Endpoint<CreateWorkflowRunRequest, ForgeAgentErrorResponse> CREATE_WORKFLOW_RUN_ERROR =
            Endpoint.createContract("/api/v1/workflows/{workflowId}/runs", HttpMethod.POST, CreateWorkflowRunRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, WorkflowRunSummaryResponse[]> LIST_WORKFLOW_RUNS =
            Endpoint.createContract("/api/v1/workflows/{workflowId}/runs", HttpMethod.GET, Void.class, WorkflowRunSummaryResponse[].class);
    public static final Endpoint<Void, WorkflowRunResponse> GET_WORKFLOW_RUN =
            Endpoint.createContract("/api/v1/workflow-runs/{runId}", HttpMethod.GET, Void.class, WorkflowRunResponse.class);

    private ForgeAgentMockMvcEndpoint() {
    }
}
