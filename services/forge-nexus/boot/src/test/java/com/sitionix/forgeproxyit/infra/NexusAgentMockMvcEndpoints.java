package com.sitionix.forgeproxyit.infra;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRequest;
import com.sitionix.forgeai.api.agentproxy.AgentLogDiscoveryRequest;
import com.sitionix.forgeai.api.agentproxy.AgentLogSourceRequest;
import com.sitionix.forgeai.api.agentproxy.AgentLogSourceResponse;
import com.sitionix.forgeai.api.agentproxy.AgentLogTargetCandidateResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRepositoryResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectTaskPageResponse;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefault;
import org.springframework.http.HttpStatus;

public final class NexusAgentMockMvcEndpoints {

    private NexusAgentMockMvcEndpoints() {
    }

    public static Endpoint<AgentProjectRequest, AgentProjectResponse> createProject() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects",
                HttpMethod.POST,
                AgentProjectRequest.class,
                AgentProjectResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-create-project-request.json")
                        .expectStatus(HttpStatus.CREATED.value())
                        .expectResponse("agent-create-project-response.json")
        );
    }

    public static Endpoint<Void, AgentProjectTaskPageResponse> listTasks() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/tasks",
                HttpMethod.GET,
                Void.class,
                AgentProjectTaskPageResponse.class,
                (MockmvcDefault) context -> context
                        .expectStatus(HttpStatus.OK.value())
                        .expectResponse("agent-task-page-response.json")
        );
    }

    public static Endpoint<Void, AgentProjectRepositoryResponse> refreshRepository() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/repositories/{repositoryId}/refresh",
                HttpMethod.POST,
                Void.class,
                AgentProjectRepositoryResponse.class,
                (MockmvcDefault) context -> context
                        .expectStatus(HttpStatus.OK.value())
                        .expectResponse("agent-refresh-repository-response.json")
        );
    }

    public static Endpoint<AgentProjectRequest, InfrastructureErrorResponse> createProjectConflict() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects",
                HttpMethod.POST,
                AgentProjectRequest.class,
                InfrastructureErrorResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-create-project-request.json")
                        .expectStatus(HttpStatus.CONFLICT.value())
                        .expectResponse("agent-upstream-error-response.json")
        );
    }

    public static Endpoint<AgentLogSourceRequest, AgentLogSourceResponse> createLogSource() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/log-sources", HttpMethod.POST,
                AgentLogSourceRequest.class, AgentLogSourceResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-create-log-source-request.json")
                        .expectStatus(HttpStatus.CREATED.value())
                        .expectResponse("agent-create-log-source-response.json"));
    }

    public static Endpoint<Void, AgentLogSourceResponse[]> listLogSources() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/log-sources", HttpMethod.GET,
                Void.class, AgentLogSourceResponse[].class,
                (MockmvcDefault) context -> context.expectStatus(HttpStatus.OK.value())
                        .expectResponse("agent-list-log-sources-response.json"));
    }

    public static Endpoint<AgentLogDiscoveryRequest, AgentLogTargetCandidateResponse[]> discoverLogs() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/log-sources/discover", HttpMethod.POST,
                AgentLogDiscoveryRequest.class, AgentLogTargetCandidateResponse[].class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-discover-logs-request.json")
                        .expectStatus(HttpStatus.OK.value())
                        .expectResponse("agent-discover-logs-response.json"));
    }

    public static Endpoint<AgentLogSourceRequest, InfrastructureErrorResponse> createLogSourceConflict() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/log-sources", HttpMethod.POST,
                AgentLogSourceRequest.class, InfrastructureErrorResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-create-log-source-request.json")
                        .expectStatus(HttpStatus.CONFLICT.value())
                        .expectResponse("agent-upstream-error-response.json"));
    }

    public static Endpoint<AgentLogSourceRequest, InfrastructureErrorResponse> invalidLogSource() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/log-sources", HttpMethod.POST,
                AgentLogSourceRequest.class, InfrastructureErrorResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-invalid-log-source-request.json")
                        .expectStatus(HttpStatus.BAD_REQUEST.value()));
    }
}
