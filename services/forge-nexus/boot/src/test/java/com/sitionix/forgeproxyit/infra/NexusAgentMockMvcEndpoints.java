package com.sitionix.forgeproxyit.infra;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRequest;
import com.sitionix.forgeai.api.agentproxy.AgentProjectResponse;
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
}
