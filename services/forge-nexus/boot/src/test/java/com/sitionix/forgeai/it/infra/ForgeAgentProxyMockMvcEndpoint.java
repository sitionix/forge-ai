package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionListResponse;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionRequest;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRequest;
import com.sitionix.forgeai.api.agentproxy.AgentProjectResponse;
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
}
