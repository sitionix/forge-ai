package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentRuntimeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateProjectTaskRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ImportProjectRepositoryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskPageResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunSummaryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.SaveAgentWorkflowRequest;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.wiremock.WiremockDefault;
import org.springframework.http.HttpStatus;

public final class ForgeAgentProxyWireMockEndpoint {

    private ForgeAgentProxyWireMockEndpoint() {
    }

    public static Endpoint<Void, AgentProjectResponse[]> listProjects() {
        return upstreamGet("/api/v1/projects",
                AgentProjectResponse[].class,
                HttpStatus.OK,
                "responseAgentProxyProjects.json");
    }

    public static Endpoint<AgentProjectRequest, AgentProjectResponse> createProject() {
        return upstreamPost("/api/v1/projects",
                AgentProjectRequest.class,
                AgentProjectResponse.class,
                "requestAgentProxyCreateProject.json",
                HttpStatus.CREATED,
                "responseAgentProxyCreateProject.json");
    }

    public static Endpoint<Void, Void> deleteProject() {
        return upstreamDelete("/api/v1/projects/{projectId}");
    }

    public static Endpoint<ImportProjectRepositoryRequest, ProjectRepositoryResponse> importProjectRepository() {
        return upstreamPost("/api/v1/projects/{projectId}/repositories",
                ImportProjectRepositoryRequest.class,
                ProjectRepositoryResponse.class,
                "requestAgentProxyImportProjectRepository.json",
                HttpStatus.CREATED,
                "responseAgentProxyProjectRepository.json");
    }

    public static Endpoint<Void, ProjectRepositoryResponse[]> listProjectRepositories() {
        return upstreamGet("/api/v1/projects/{projectId}/repositories",
                ProjectRepositoryResponse[].class,
                HttpStatus.OK,
                "responseAgentProxyProjectRepositories.json");
    }

    public static Endpoint<Void, ProjectRepositoryResponse> cloneProjectRepository() {
        return Endpoint.createContract("/api/v1/projects/{projectId}/repositories/{repositoryId}/clone",
                HttpMethod.POST,
                Void.class,
                ProjectRepositoryResponse.class,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseAgentProxyProjectRepositoryCloned.json"));
    }

    public static Endpoint<Void, ProjectRepositoryResponse> pullProjectRepository() {
        return Endpoint.createContract("/api/v1/projects/{projectId}/repositories/{repositoryId}/pull",
                HttpMethod.POST,
                Void.class,
                ProjectRepositoryResponse.class,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseAgentProxyProjectRepositoryCloned.json"));
    }

    public static Endpoint<CreateProjectTaskRequest, ProjectTaskResponse> createProjectTask() {
        return upstreamPost("/api/v1/projects/{projectId}/tasks",
                CreateProjectTaskRequest.class,
                ProjectTaskResponse.class,
                "requestAgentProxyCreateProjectTask.json",
                HttpStatus.CREATED,
                "responseAgentProxyProjectTask.json");
    }

    public static Endpoint<CreateProjectTaskRequest, InfrastructureErrorResponse> createProjectTaskEmptyWorkflow() {
        return upstreamPost("/api/v1/projects/{projectId}/tasks",
                CreateProjectTaskRequest.class,
                InfrastructureErrorResponse.class,
                "requestAgentProxyCreateProjectTask.json",
                HttpStatus.CONFLICT,
                "responseAgentProxyEmptyWorkflow.json");
    }

    public static Endpoint<Void, ProjectTaskPageResponse> listProjectTasks() {
        return upstreamGet("/api/v1/projects/{projectId}/tasks",
                ProjectTaskPageResponse.class,
                HttpStatus.OK,
                "responseAgentProxyProjectTasks.json");
    }

    public static Endpoint<Void, ProjectTaskResponse> getProjectTask() {
        return upstreamGet("/api/v1/tasks/{taskId}",
                ProjectTaskResponse.class,
                HttpStatus.OK,
                "responseAgentProxyProjectTask.json");
    }

    public static Endpoint<Void, Void> deleteProjectTask() {
        return upstreamDelete("/api/v1/tasks/{taskId}");
    }

    public static Endpoint<Void, AgentRuntimeResponse> getRuntime() {
        return upstreamGet("/api/v1/runtime",
                AgentRuntimeResponse.class,
                HttpStatus.OK,
                "responseAgentProxyRuntime.json");
    }

    public static Endpoint<Void, AgentDefinitionListResponse[]> listProjectAgents() {
        return upstreamGet("/api/v1/projects/{projectId}/agents",
                AgentDefinitionListResponse[].class,
                HttpStatus.OK,
                "responseAgentProxyProjectAgents.json");
    }

    public static Endpoint<AgentDefinitionRequest, AgentDefinitionResponse> createAgent() {
        return upstreamPost("/api/v1/projects/{projectId}/agents",
                AgentDefinitionRequest.class,
                AgentDefinitionResponse.class,
                "requestAgentProxySaveAgent.json",
                HttpStatus.CREATED,
                "responseAgentProxyAgent.json");
    }

    public static Endpoint<Void, AgentDefinitionResponse> getAgent() {
        return upstreamGet("/api/v1/agents/{agentId}",
                AgentDefinitionResponse.class,
                HttpStatus.OK,
                "responseAgentProxyAgent.json");
    }

    public static Endpoint<AgentDefinitionRequest, AgentDefinitionResponse> updateAgent() {
        return upstreamPut("/api/v1/agents/{agentId}",
                AgentDefinitionRequest.class,
                AgentDefinitionResponse.class,
                "requestAgentProxySaveAgent.json",
                HttpStatus.OK,
                "responseAgentProxyAgentUpdated.json");
    }

    public static Endpoint<Void, Void> deleteAgent() {
        return upstreamDelete("/api/v1/agents/{agentId}");
    }

    public static Endpoint<AgentDefinitionRequest, InfrastructureErrorResponse> createAgentValidationError() {
        return upstreamPost("/api/v1/projects/{projectId}/agents",
                AgentDefinitionRequest.class,
                InfrastructureErrorResponse.class,
                "requestAgentProxySaveAgent.json",
                HttpStatus.CONFLICT,
                "responseAgentProxyValidationError.json");
    }

    public static Endpoint<Void, AgentDefinitionResponse> getAgentMalformedSuccess() {
        return upstreamGet("/api/v1/agents/{agentId}",
                AgentDefinitionResponse.class,
                HttpStatus.OK,
                "responseAgentProxyMalformedAgent.json");
    }

    public static Endpoint<Void, AgentWorkflowResponse[]> listProjectWorkflows() {
        return upstreamGet("/api/v1/projects/{projectId}/workflows",
                AgentWorkflowResponse[].class,
                HttpStatus.OK,
                "responseAgentProxyWorkflows.json");
    }

    public static Endpoint<AgentWorkflowRequest, AgentWorkflowResponse> createWorkflow() {
        return upstreamPost("/api/v1/projects/{projectId}/workflows",
                AgentWorkflowRequest.class,
                AgentWorkflowResponse.class,
                "requestAgentProxyCreateWorkflow.json",
                HttpStatus.CREATED,
                "responseAgentProxyWorkflow.json");
    }

    public static Endpoint<Void, AgentWorkflowResponse> getWorkflow() {
        return upstreamGet("/api/v1/workflows/{workflowId}",
                AgentWorkflowResponse.class,
                HttpStatus.OK,
                "responseAgentProxyWorkflow.json");
    }

    public static Endpoint<SaveAgentWorkflowRequest, AgentWorkflowResponse> updateWorkflow() {
        return upstreamPut("/api/v1/workflows/{workflowId}",
                SaveAgentWorkflowRequest.class,
                AgentWorkflowResponse.class,
                "requestAgentProxyUpdateWorkflow.json",
                HttpStatus.OK,
                "responseAgentProxyWorkflowUpdated.json");
    }

    public static Endpoint<Void, Void> deleteWorkflow() {
        return upstreamDelete("/api/v1/workflows/{workflowId}");
    }

    public static Endpoint<CreateWorkflowRunRequest, WorkflowRunResponse> createWorkflowRun() {
        return upstreamPost("/api/v1/workflows/{workflowId}/runs",
                CreateWorkflowRunRequest.class,
                WorkflowRunResponse.class,
                "requestAgentProxyCreateWorkflowRun.json",
                HttpStatus.CREATED,
                "responseAgentProxyWorkflowRun.json");
    }

    public static Endpoint<Void, WorkflowRunSummaryResponse[]> listWorkflowRuns() {
        return upstreamGet("/api/v1/workflows/{workflowId}/runs",
                WorkflowRunSummaryResponse[].class,
                HttpStatus.OK,
                "responseAgentProxyWorkflowRuns.json");
    }

    public static Endpoint<Void, WorkflowRunResponse> getWorkflowRun() {
        return upstreamGet("/api/v1/workflow-runs/{runId}",
                WorkflowRunResponse.class,
                HttpStatus.OK,
                "responseAgentProxyWorkflowRun.json");
    }

    public static Endpoint<CreateWorkflowRunRequest, InfrastructureErrorResponse> createWorkflowRunValidationError() {
        return upstreamPost("/api/v1/workflows/{workflowId}/runs",
                CreateWorkflowRunRequest.class,
                InfrastructureErrorResponse.class,
                "requestAgentProxyCreateWorkflowRun.json",
                HttpStatus.CONFLICT,
                "responseAgentProxyValidationError.json");
    }

    private static <Res> Endpoint<Void, Res> upstreamGet(final String path,
                                                         final Class<Res> responseClass,
                                                         final HttpStatus status,
                                                         final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.GET, Void.class, responseClass,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .responseStatus(status.value())
                        .responseBody(responseFixture));
    }

    private static <Req, Res> Endpoint<Req, Res> upstreamPost(final String path,
                                                              final Class<Req> requestClass,
                                                              final Class<Res> responseClass,
                                                              final String requestFixture,
                                                              final HttpStatus status,
                                                              final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.POST, requestClass, responseClass,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .matchesJson(requestFixture)
                        .responseStatus(status.value())
                        .responseBody(responseFixture));
    }

    private static <Req, Res> Endpoint<Req, Res> upstreamPut(final String path,
                                                             final Class<Req> requestClass,
                                                             final Class<Res> responseClass,
                                                             final String requestFixture,
                                                             final HttpStatus status,
                                                             final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.PUT, requestClass, responseClass,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .matchesJson(requestFixture)
                        .responseStatus(status.value())
                        .responseBody(responseFixture));
    }

    private static Endpoint<Void, Void> upstreamDelete(final String path) {
        return Endpoint.createContract(path, HttpMethod.DELETE, Void.class, Void.class,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .responseStatus(HttpStatus.NO_CONTENT.value()));
    }
}
