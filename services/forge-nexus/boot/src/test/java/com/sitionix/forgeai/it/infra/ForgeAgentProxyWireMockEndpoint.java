package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowResponse;
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
}
