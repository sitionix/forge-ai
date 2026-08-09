package com.sitionix.forgeagent.it.infra;

import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.ForgeAgentErrorResponse;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;

public final class ForgeAgentMockMvcEndpoint {

    private ForgeAgentMockMvcEndpoint() {
    }

    public static Endpoint<Void, ProjectResponse[]> listProjects() {
        return Endpoint.createContract("/api/v1/projects", HttpMethod.GET, Void.class, ProjectResponse[].class);
    }

    public static Endpoint<CreateProjectRequest, ProjectResponse> createProject() {
        return Endpoint.createContract("/api/v1/projects", HttpMethod.POST, CreateProjectRequest.class, ProjectResponse.class);
    }

    public static Endpoint<CreateProjectRequest, ForgeAgentErrorResponse> createProjectError() {
        return Endpoint.createContract("/api/v1/projects", HttpMethod.POST, CreateProjectRequest.class, ForgeAgentErrorResponse.class);
    }

    public static Endpoint<Void, AgentListResponse[]> listProjectAgents() {
        return Endpoint.createContract("/api/v1/projects/{projectId}/agents", HttpMethod.GET, Void.class, AgentListResponse[].class);
    }

    public static Endpoint<Void, ForgeAgentErrorResponse> listProjectAgentsError() {
        return Endpoint.createContract("/api/v1/projects/{projectId}/agents", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    }

    public static Endpoint<SaveAgentRequest, AgentResponse> createAgent() {
        return Endpoint.createContract("/api/v1/projects/{projectId}/agents", HttpMethod.POST, SaveAgentRequest.class, AgentResponse.class);
    }

    public static Endpoint<SaveAgentRequest, ForgeAgentErrorResponse> createAgentError() {
        return Endpoint.createContract("/api/v1/projects/{projectId}/agents", HttpMethod.POST, SaveAgentRequest.class, ForgeAgentErrorResponse.class);
    }

    public static Endpoint<Void, AgentResponse> getAgent() {
        return Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.GET, Void.class, AgentResponse.class);
    }

    public static Endpoint<Void, ForgeAgentErrorResponse> getAgentError() {
        return Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    }

    public static Endpoint<SaveAgentRequest, AgentResponse> updateAgent() {
        return Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.PUT, SaveAgentRequest.class, AgentResponse.class);
    }

    public static Endpoint<SaveAgentRequest, ForgeAgentErrorResponse> updateAgentError() {
        return Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.PUT, SaveAgentRequest.class, ForgeAgentErrorResponse.class);
    }

    public static Endpoint<SaveAgentRequest, Void> updateAgentUntyped() {
        return Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.PUT, SaveAgentRequest.class, Void.class);
    }
}
