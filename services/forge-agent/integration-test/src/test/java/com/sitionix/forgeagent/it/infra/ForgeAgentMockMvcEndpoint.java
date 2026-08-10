package com.sitionix.forgeagent.it.infra;

import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRequest;
import com.sitionix.forgeagent.api.dto.ForgeAgentErrorResponse;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.api.dto.SaveWorkflowRequest;
import com.sitionix.forgeagent.api.dto.WorkflowResponse;
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

    public static Endpoint<Void, WorkflowResponse[]> listProjectWorkflows() {
        return Endpoint.createContract("/api/v1/projects/{projectId}/workflows", HttpMethod.GET, Void.class, WorkflowResponse[].class);
    }

    public static Endpoint<Void, ForgeAgentErrorResponse> listProjectWorkflowsError() {
        return Endpoint.createContract("/api/v1/projects/{projectId}/workflows", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    }

    public static Endpoint<CreateWorkflowRequest, WorkflowResponse> createWorkflow() {
        return Endpoint.createContract("/api/v1/projects/{projectId}/workflows", HttpMethod.POST, CreateWorkflowRequest.class, WorkflowResponse.class);
    }

    public static Endpoint<CreateWorkflowRequest, ForgeAgentErrorResponse> createWorkflowError() {
        return Endpoint.createContract("/api/v1/projects/{projectId}/workflows", HttpMethod.POST, CreateWorkflowRequest.class, ForgeAgentErrorResponse.class);
    }

    public static Endpoint<Void, WorkflowResponse> getWorkflow() {
        return Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.GET, Void.class, WorkflowResponse.class);
    }

    public static Endpoint<Void, ForgeAgentErrorResponse> getWorkflowError() {
        return Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    }

    public static Endpoint<SaveWorkflowRequest, WorkflowResponse> updateWorkflow() {
        return Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.PUT, SaveWorkflowRequest.class, WorkflowResponse.class);
    }

    public static Endpoint<SaveWorkflowRequest, ForgeAgentErrorResponse> updateWorkflowError() {
        return Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.PUT, SaveWorkflowRequest.class, ForgeAgentErrorResponse.class);
    }
}
