package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionListResponse;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionRequest;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionResponse;
import com.sitionix.forgeai.api.agentproxy.AgentRuntimeResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRepositoryResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectTaskResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectTaskPageResponse;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowRunResponse;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowRunSummaryResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRequest;
import com.sitionix.forgeai.api.agentproxy.AgentProjectResponse;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowRequest;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowResponse;
import com.sitionix.forgeai.api.agentproxy.CreateAgentWorkflowRunRequest;
import com.sitionix.forgeai.api.agentproxy.CreateAgentProjectTaskRequest;
import com.sitionix.forgeai.api.agentproxy.ImportAgentProjectRepositoryRequest;
import com.sitionix.forgeai.api.agentproxy.SaveAgentWorkflowRequest;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefault;
import org.springframework.http.HttpStatus;

public final class ForgeAgentProxyMockMvcEndpoint {

    private ForgeAgentProxyMockMvcEndpoint() {
    }

    public static Endpoint<Void, AgentProjectResponse[]> listProjects() {
        return nexusGet("/api/v1/infrastructure/agents/projects",
                AgentProjectResponse[].class,
                HttpStatus.OK,
                "responseAgentProxyProjects.json");
    }

    public static Endpoint<AgentProjectRequest, AgentProjectResponse> createProject() {
        return nexusPost("/api/v1/infrastructure/agents/projects",
                AgentProjectRequest.class,
                AgentProjectResponse.class,
                "requestAgentProxyCreateProject.json",
                HttpStatus.CREATED,
                "responseAgentProxyCreateProject.json");
    }

    public static Endpoint<Void, Void> deleteProject() {
        return nexusDelete("/api/v1/infrastructure/agents/projects/{projectId}");
    }

    public static Endpoint<ImportAgentProjectRepositoryRequest, AgentProjectRepositoryResponse> importProjectRepository() {
        return nexusPost("/api/v1/infrastructure/agents/projects/{projectId}/repositories",
                ImportAgentProjectRepositoryRequest.class,
                AgentProjectRepositoryResponse.class,
                "requestAgentProxyImportProjectRepository.json",
                HttpStatus.CREATED,
                "responseAgentProxyProjectRepository.json");
    }

    public static Endpoint<Void, AgentProjectRepositoryResponse[]> listProjectRepositories() {
        return nexusGet("/api/v1/infrastructure/agents/projects/{projectId}/repositories",
                AgentProjectRepositoryResponse[].class,
                HttpStatus.OK,
                "responseAgentProxyProjectRepositories.json");
    }

    public static Endpoint<Void, AgentProjectRepositoryResponse> cloneProjectRepository() {
        return Endpoint.createContract("/api/v1/infrastructure/agents/projects/{projectId}/repositories/{repositoryId}/clone",
                HttpMethod.POST,
                Void.class,
                AgentProjectRepositoryResponse.class,
                (MockmvcDefault) context -> context
                        .expectStatus(HttpStatus.OK.value())
                        .expectResponse("responseAgentProxyProjectRepositoryCloned.json"));
    }

    public static Endpoint<Void, AgentProjectRepositoryResponse> pullProjectRepository() {
        return Endpoint.createContract("/api/v1/infrastructure/agents/projects/{projectId}/repositories/{repositoryId}/pull",
                HttpMethod.POST,
                Void.class,
                AgentProjectRepositoryResponse.class,
                (MockmvcDefault) context -> context
                        .expectStatus(HttpStatus.OK.value())
                        .expectResponse("responseAgentProxyProjectRepositoryCloned.json"));
    }

    public static Endpoint<CreateAgentProjectTaskRequest, AgentProjectTaskResponse> createProjectTask() {
        return nexusPost("/api/v1/infrastructure/agents/projects/{projectId}/tasks",
                CreateAgentProjectTaskRequest.class,
                AgentProjectTaskResponse.class,
                "requestAgentProxyCreateProjectTask.json",
                HttpStatus.CREATED,
                "responseAgentProxyProjectTask.json");
    }

    public static Endpoint<CreateAgentProjectTaskRequest, InfrastructureErrorResponse> createProjectTaskLocalInvalid() {
        return nexusPost("/api/v1/infrastructure/agents/projects/{projectId}/tasks",
                CreateAgentProjectTaskRequest.class,
                InfrastructureErrorResponse.class,
                "requestAgentProxyInvalidProjectTask.json",
                HttpStatus.BAD_REQUEST,
                "responseAgentProxyLocalInvalidRequest.json");
    }

    public static Endpoint<CreateAgentProjectTaskRequest, InfrastructureErrorResponse> createProjectTaskEmptyWorkflow() {
        return nexusPost("/api/v1/infrastructure/agents/projects/{projectId}/tasks",
                CreateAgentProjectTaskRequest.class,
                InfrastructureErrorResponse.class,
                "requestAgentProxyCreateProjectTask.json",
                HttpStatus.CONFLICT,
                "responseAgentProxyEmptyWorkflow.json");
    }

    public static Endpoint<CreateAgentProjectTaskRequest, InfrastructureErrorResponse> createProjectTaskRepositoryNotFound() {
        return nexusPost("/api/v1/infrastructure/agents/projects/{projectId}/tasks",
                CreateAgentProjectTaskRequest.class,
                InfrastructureErrorResponse.class,
                "requestAgentProxyCreateProjectTask.json",
                HttpStatus.NOT_FOUND,
                "responseAgentProxyRepositoryNotFound.json");
    }

    public static Endpoint<Void, AgentProjectTaskPageResponse> listProjectTasks() {
        return nexusGet("/api/v1/infrastructure/agents/projects/{projectId}/tasks",
                AgentProjectTaskPageResponse.class,
                HttpStatus.OK,
                "responseAgentProxyProjectTasks.json");
    }

    public static Endpoint<Void, AgentProjectTaskResponse> getProjectTask() {
        return nexusGet("/api/v1/infrastructure/agents/tasks/{taskId}",
                AgentProjectTaskResponse.class,
                HttpStatus.OK,
                "responseAgentProxyProjectTask.json");
    }

    public static Endpoint<Void, Void> deleteProjectTask() {
        return nexusDelete("/api/v1/infrastructure/agents/tasks/{taskId}");
    }

    public static Endpoint<Void, AgentRuntimeResponse> getRuntime() {
        return nexusGet("/api/v1/infrastructure/agents/runtime",
                AgentRuntimeResponse.class,
                HttpStatus.OK,
                "responseAgentProxyRuntime.json");
    }

    public static Endpoint<Void, AgentDefinitionListResponse[]> listProjectAgents() {
        return nexusGet("/api/v1/infrastructure/agents/projects/{projectId}/agents",
                AgentDefinitionListResponse[].class,
                HttpStatus.OK,
                "responseAgentProxyProjectAgents.json");
    }

    public static Endpoint<AgentDefinitionRequest, AgentDefinitionResponse> createAgent() {
        return nexusPost("/api/v1/infrastructure/agents/projects/{projectId}/agents",
                AgentDefinitionRequest.class,
                AgentDefinitionResponse.class,
                "requestAgentProxySaveAgent.json",
                HttpStatus.CREATED,
                "responseAgentProxyAgent.json");
    }

    public static Endpoint<Void, AgentDefinitionResponse> getAgent() {
        return nexusGet("/api/v1/infrastructure/agents/definitions/{agentId}",
                AgentDefinitionResponse.class,
                HttpStatus.OK,
                "responseAgentProxyAgent.json");
    }

    public static Endpoint<AgentDefinitionRequest, AgentDefinitionResponse> updateAgent() {
        return nexusPut("/api/v1/infrastructure/agents/definitions/{agentId}",
                AgentDefinitionRequest.class,
                AgentDefinitionResponse.class,
                "requestAgentProxySaveAgent.json",
                HttpStatus.OK,
                "responseAgentProxyAgentUpdated.json");
    }

    public static Endpoint<Void, Void> deleteAgent() {
        return nexusDelete("/api/v1/infrastructure/agents/definitions/{agentId}");
    }

    public static Endpoint<AgentDefinitionRequest, InfrastructureErrorResponse> createAgentValidationError() {
        return nexusPost("/api/v1/infrastructure/agents/projects/{projectId}/agents",
                AgentDefinitionRequest.class,
                InfrastructureErrorResponse.class,
                "requestAgentProxySaveAgent.json",
                HttpStatus.CONFLICT,
                "responseAgentProxyValidationError.json");
    }

    public static Endpoint<Void, InfrastructureErrorResponse> getAgentMalformedSuccess() {
        return nexusGet("/api/v1/infrastructure/agents/definitions/{agentId}",
                InfrastructureErrorResponse.class,
                HttpStatus.BAD_GATEWAY,
                "responseAgentProxyUpstreamInvalidResponse.json");
    }

    public static Endpoint<Void, InfrastructureErrorResponse> getAgentUpstreamUnavailable() {
        return nexusGet("/api/v1/infrastructure/agents/definitions/{agentId}",
                InfrastructureErrorResponse.class,
                HttpStatus.SERVICE_UNAVAILABLE,
                "responseAgentProxyUpstreamUnavailable.json");
    }

    public static Endpoint<AgentDefinitionRequest, InfrastructureErrorResponse> createAgentLocalInvalid() {
        return nexusPost("/api/v1/infrastructure/agents/projects/{projectId}/agents",
                AgentDefinitionRequest.class,
                InfrastructureErrorResponse.class,
                "requestAgentProxyInvalidAgent.json",
                HttpStatus.BAD_REQUEST,
                "responseAgentProxyLocalInvalidRequest.json");
    }

    public static Endpoint<Void, AgentWorkflowResponse[]> listProjectWorkflows() {
        return nexusGet("/api/v1/infrastructure/agents/projects/{projectId}/workflows",
                AgentWorkflowResponse[].class,
                HttpStatus.OK,
                "responseAgentProxyWorkflows.json");
    }

    public static Endpoint<AgentWorkflowRequest, AgentWorkflowResponse> createWorkflow() {
        return nexusPost("/api/v1/infrastructure/agents/projects/{projectId}/workflows",
                AgentWorkflowRequest.class,
                AgentWorkflowResponse.class,
                "requestAgentProxyCreateWorkflow.json",
                HttpStatus.CREATED,
                "responseAgentProxyWorkflow.json");
    }

    public static Endpoint<Void, AgentWorkflowResponse> getWorkflow() {
        return nexusGet("/api/v1/infrastructure/agents/workflows/{workflowId}",
                AgentWorkflowResponse.class,
                HttpStatus.OK,
                "responseAgentProxyWorkflow.json");
    }

    public static Endpoint<SaveAgentWorkflowRequest, AgentWorkflowResponse> updateWorkflow() {
        return nexusPut("/api/v1/infrastructure/agents/workflows/{workflowId}",
                SaveAgentWorkflowRequest.class,
                AgentWorkflowResponse.class,
                "requestAgentProxyUpdateWorkflow.json",
                HttpStatus.OK,
                "responseAgentProxyWorkflowUpdated.json");
    }

    public static Endpoint<Void, Void> deleteWorkflow() {
        return nexusDelete("/api/v1/infrastructure/agents/workflows/{workflowId}");
    }

    public static Endpoint<CreateAgentWorkflowRunRequest, AgentWorkflowRunResponse> createWorkflowRun() {
        return nexusPost("/api/v1/infrastructure/agents/workflows/{workflowId}/runs",
                CreateAgentWorkflowRunRequest.class,
                AgentWorkflowRunResponse.class,
                "requestAgentProxyCreateWorkflowRun.json",
                HttpStatus.CREATED,
                "responseAgentProxyWorkflowRun.json");
    }

    public static Endpoint<Void, AgentWorkflowRunSummaryResponse[]> listWorkflowRuns() {
        return nexusGet("/api/v1/infrastructure/agents/workflows/{workflowId}/runs",
                AgentWorkflowRunSummaryResponse[].class,
                HttpStatus.OK,
                "responseAgentProxyWorkflowRuns.json");
    }

    public static Endpoint<Void, AgentWorkflowRunResponse> getWorkflowRun() {
        return nexusGet("/api/v1/infrastructure/agents/workflow-runs/{runId}",
                AgentWorkflowRunResponse.class,
                HttpStatus.OK,
                "responseAgentProxyWorkflowRun.json");
    }

    public static Endpoint<CreateAgentWorkflowRunRequest, InfrastructureErrorResponse> createWorkflowRunValidationError() {
        return nexusPost("/api/v1/infrastructure/agents/workflows/{workflowId}/runs",
                CreateAgentWorkflowRunRequest.class,
                InfrastructureErrorResponse.class,
                "requestAgentProxyCreateWorkflowRun.json",
                HttpStatus.CONFLICT,
                "responseAgentProxyValidationError.json");
    }

    private static <Res> Endpoint<Void, Res> nexusGet(final String path,
                                                      final Class<Res> responseClass,
                                                      final HttpStatus status,
                                                      final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.GET, Void.class, responseClass,
                (MockmvcDefault) context -> context
                        .expectStatus(status.value())
                        .expectResponse(responseFixture));
    }

    private static <Req, Res> Endpoint<Req, Res> nexusPost(final String path,
                                                           final Class<Req> requestClass,
                                                           final Class<Res> responseClass,
                                                           final String requestFixture,
                                                           final HttpStatus status,
                                                           final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.POST, requestClass, responseClass,
                (MockmvcDefault) context -> context
                        .withRequest(requestFixture)
                        .expectStatus(status.value())
                        .expectResponse(responseFixture));
    }

    private static <Req, Res> Endpoint<Req, Res> nexusPut(final String path,
                                                          final Class<Req> requestClass,
                                                          final Class<Res> responseClass,
                                                          final String requestFixture,
                                                          final HttpStatus status,
                                                          final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.PUT, requestClass, responseClass,
                (MockmvcDefault) context -> context
                        .withRequest(requestFixture)
                        .expectStatus(status.value())
                        .expectResponse(responseFixture));
    }

    private static Endpoint<Void, Void> nexusDelete(final String path) {
        return Endpoint.createContract(path, HttpMethod.DELETE, Void.class, Void.class,
                (MockmvcDefault) context -> context
                        .expectStatus(HttpStatus.NO_CONTENT.value()));
    }
}
