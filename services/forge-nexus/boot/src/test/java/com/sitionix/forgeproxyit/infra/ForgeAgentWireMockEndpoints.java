package com.sitionix.forgeproxyit.infra;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskPageResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryResponse;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.wiremock.WiremockDefault;
import org.springframework.http.HttpStatus;

public final class ForgeAgentWireMockEndpoints {

    private ForgeAgentWireMockEndpoints() {
    }

    public static Endpoint<AgentProjectRequest, AgentProjectResponse> createProject() {
        return upstreamPost(AgentProjectResponse.class, HttpStatus.CREATED, "agent-create-project-response.json");
    }

    public static Endpoint<Void, ProjectTaskPageResponse> listTasks() {
        return Endpoint.createContract(
                "/api/v1/projects/{projectId}/tasks",
                HttpMethod.GET,
                Void.class,
                ProjectTaskPageResponse.class,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("agent-task-page-response.json")
        );
    }

    public static Endpoint<Void, ProjectRepositoryResponse> refreshRepository() {
        return Endpoint.createContract(
                "/api/v1/projects/{projectId}/repositories/{repositoryId}/refresh",
                HttpMethod.POST,
                Void.class,
                ProjectRepositoryResponse.class,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("agent-refresh-repository-response.json")
        );
    }

    public static Endpoint<AgentProjectRequest, InfrastructureErrorResponse> createProjectConflict() {
        return upstreamPost(InfrastructureErrorResponse.class, HttpStatus.CONFLICT,
                "agent-upstream-error-response.json");
    }

    private static <Response> Endpoint<AgentProjectRequest, Response> upstreamPost(
            final Class<Response> responseType,
            final HttpStatus status,
            final String responseFixture
    ) {
        return Endpoint.createContract(
                "/api/v1/projects",
                HttpMethod.POST,
                AgentProjectRequest.class,
                responseType,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .matchesJson("agent-create-project-request.json")
                        .responseStatus(status.value())
                        .responseBody(responseFixture)
        );
    }
}
