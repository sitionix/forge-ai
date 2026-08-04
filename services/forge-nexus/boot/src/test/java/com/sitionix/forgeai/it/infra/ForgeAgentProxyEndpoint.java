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
import com.sitionix.forgeit.domain.endpoint.wiremock.WiremockDefault;
import org.springframework.http.HttpStatus;

public final class ForgeAgentProxyEndpoint {

    public static final Endpoint<Object, AgentProjectResponse[]> NEXUS_LIST_PROJECTS =
            nexusGet("/api/v1/infrastructure/agents/projects", AgentProjectResponse[].class, HttpStatus.OK, "responseAgentProxyProjects.json");

    public static final Endpoint<AgentProjectRequest, AgentProjectResponse> NEXUS_CREATE_PROJECT =
            nexusPost("/api/v1/infrastructure/agents/projects", AgentProjectRequest.class, AgentProjectResponse.class,
                    "requestAgentProxyCreateProject.json", HttpStatus.CREATED, "responseAgentProxyCreateProject.json");

    public static final Endpoint<Object, AgentDefinitionListResponse[]> NEXUS_LIST_PROJECT_AGENTS =
            nexusGet("/api/v1/infrastructure/agents/projects/11111111-1111-4111-8111-111111111111/agents",
                    AgentDefinitionListResponse[].class, HttpStatus.OK, "responseAgentProxyProjectAgents.json");

    public static final Endpoint<AgentDefinitionRequest, AgentDefinitionResponse> NEXUS_CREATE_AGENT =
            nexusPost("/api/v1/infrastructure/agents/projects/11111111-1111-4111-8111-111111111111/agents",
                    AgentDefinitionRequest.class, AgentDefinitionResponse.class,
                    "requestAgentProxySaveAgent.json", HttpStatus.CREATED, "responseAgentProxyAgent.json");

    public static final Endpoint<Object, AgentDefinitionResponse> NEXUS_GET_AGENT =
            nexusGet("/api/v1/infrastructure/agents/definitions/22222222-2222-4222-8222-222222222222",
                    AgentDefinitionResponse.class, HttpStatus.OK, "responseAgentProxyAgent.json");

    public static final Endpoint<AgentDefinitionRequest, AgentDefinitionResponse> NEXUS_UPDATE_AGENT =
            nexusPut("/api/v1/infrastructure/agents/definitions/22222222-2222-4222-8222-222222222222",
                    AgentDefinitionRequest.class, AgentDefinitionResponse.class,
                    "requestAgentProxySaveAgent.json", HttpStatus.OK, "responseAgentProxyAgentUpdated.json");

    public static final Endpoint<AgentDefinitionRequest, InfrastructureErrorResponse> NEXUS_CREATE_AGENT_VALIDATION_ERROR =
            nexusPost("/api/v1/infrastructure/agents/projects/11111111-1111-4111-8111-111111111111/agents",
                    AgentDefinitionRequest.class, InfrastructureErrorResponse.class,
                    "requestAgentProxySaveAgent.json", HttpStatus.CONFLICT, "responseAgentProxyValidationError.json");

    public static final Endpoint<Object, InfrastructureErrorResponse> NEXUS_GET_AGENT_MALFORMED_SUCCESS =
            nexusGet("/api/v1/infrastructure/agents/definitions/22222222-2222-4222-8222-222222222222",
                    InfrastructureErrorResponse.class, HttpStatus.BAD_GATEWAY, "responseAgentProxyUpstreamInvalidResponse.json");

    public static final Endpoint<Object, InfrastructureErrorResponse> NEXUS_GET_AGENT_UPSTREAM_UNAVAILABLE =
            nexusGet("/api/v1/infrastructure/agents/definitions/22222222-2222-4222-8222-222222222222",
                    InfrastructureErrorResponse.class, HttpStatus.SERVICE_UNAVAILABLE, "responseAgentProxyUpstreamUnavailable.json");

    public static final Endpoint<AgentDefinitionRequest, InfrastructureErrorResponse> NEXUS_CREATE_AGENT_LOCAL_INVALID =
            nexusPost("/api/v1/infrastructure/agents/projects/11111111-1111-4111-8111-111111111111/agents",
                    AgentDefinitionRequest.class, InfrastructureErrorResponse.class,
                    "requestAgentProxyInvalidAgent.json", HttpStatus.BAD_REQUEST, "responseAgentProxyLocalInvalidRequest.json");

    public static final Endpoint<Object, com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse[]> UPSTREAM_LIST_PROJECTS =
            upstreamGet("/api/v1/projects", com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse[].class,
                    HttpStatus.OK, "responseAgentProxyProjects.json");

    public static final Endpoint<com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest, com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse> UPSTREAM_CREATE_PROJECT =
            upstreamPost("/api/v1/projects", com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest.class,
                    com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse.class,
                    "requestAgentProxyCreateProject.json", HttpStatus.CREATED, "responseAgentProxyCreateProject.json");

    public static final Endpoint<Object, com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse[]> UPSTREAM_LIST_PROJECT_AGENTS =
            upstreamGet("/api/v1/projects/11111111-1111-4111-8111-111111111111/agents",
                    com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse[].class,
                    HttpStatus.OK, "responseAgentProxyProjectAgents.json");

    public static final Endpoint<com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest, com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse> UPSTREAM_CREATE_AGENT =
            upstreamPost("/api/v1/projects/11111111-1111-4111-8111-111111111111/agents",
                    com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest.class,
                    com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse.class,
                    "requestAgentProxySaveAgent.json", HttpStatus.CREATED, "responseAgentProxyAgent.json");

    public static final Endpoint<Object, com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse> UPSTREAM_GET_AGENT =
            upstreamGet("/api/v1/agents/22222222-2222-4222-8222-222222222222",
                    com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse.class,
                    HttpStatus.OK, "responseAgentProxyAgent.json");

    public static final Endpoint<com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest, com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse> UPSTREAM_UPDATE_AGENT =
            upstreamPut("/api/v1/agents/22222222-2222-4222-8222-222222222222",
                    com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest.class,
                    com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse.class,
                    "requestAgentProxySaveAgent.json", HttpStatus.OK, "responseAgentProxyAgentUpdated.json");

    public static final Endpoint<com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest, InfrastructureErrorResponse> UPSTREAM_CREATE_AGENT_VALIDATION_ERROR =
            upstreamPost("/api/v1/projects/11111111-1111-4111-8111-111111111111/agents",
                    com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest.class,
                    InfrastructureErrorResponse.class,
                    "requestAgentProxySaveAgent.json", HttpStatus.CONFLICT, "responseAgentProxyValidationError.json");

    public static final Endpoint<Object, com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse> UPSTREAM_GET_AGENT_MALFORMED_SUCCESS =
            upstreamGet("/api/v1/agents/22222222-2222-4222-8222-222222222222",
                    com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse.class,
                    HttpStatus.OK, "responseAgentProxyMalformedAgent.json");

    private static <Res> Endpoint<Object, Res> nexusGet(final String path,
                                                        final Class<Res> responseClass,
                                                        final HttpStatus status,
                                                        final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.GET, Object.class, responseClass,
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

    private static <Res> Endpoint<Object, Res> upstreamGet(final String path,
                                                           final Class<Res> responseClass,
                                                           final HttpStatus status,
                                                           final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.GET, Object.class, responseClass,
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

    private ForgeAgentProxyEndpoint() {
    }
}
