package com.sitionix.forgeagent.it.infra;

import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.AiRuntimeResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.CreateProjectTaskRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRequest;
import com.sitionix.forgeagent.api.dto.ForgeAgentErrorResponse;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.ProjectTaskResponse;
import com.sitionix.forgeagent.api.dto.ProjectTaskSummaryResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.api.dto.SaveWorkflowRequest;
import com.sitionix.forgeagent.api.dto.WorkflowRunResponse;
import com.sitionix.forgeagent.api.dto.WorkflowRunSummaryResponse;
import com.sitionix.forgeagent.api.dto.WorkflowResponse;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;

public final class ForgeAgentMockMvcEndpoint {

    public static final Endpoint<Void, ProjectResponse[]> LIST_PROJECTS =
            Endpoint.createContract("/api/v1/projects", HttpMethod.GET, Void.class, ProjectResponse[].class);
    public static final Endpoint<CreateProjectRequest, ProjectResponse> CREATE_PROJECT =
            Endpoint.createContract("/api/v1/projects", HttpMethod.POST, CreateProjectRequest.class, ProjectResponse.class);
    public static final Endpoint<CreateProjectRequest, ForgeAgentErrorResponse> CREATE_PROJECT_ERROR =
            Endpoint.createContract("/api/v1/projects", HttpMethod.POST, CreateProjectRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<CreateProjectTaskRequest, ProjectTaskResponse> CREATE_PROJECT_TASK =
            Endpoint.createContract("/api/v1/projects/{projectId}/tasks", HttpMethod.POST, CreateProjectTaskRequest.class, ProjectTaskResponse.class);
    public static final Endpoint<CreateProjectTaskRequest, ForgeAgentErrorResponse> CREATE_PROJECT_TASK_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/tasks", HttpMethod.POST, CreateProjectTaskRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, ProjectTaskSummaryResponse[]> LIST_PROJECT_TASKS =
            Endpoint.createContract("/api/v1/projects/{projectId}/tasks", HttpMethod.GET, Void.class, ProjectTaskSummaryResponse[].class);
    public static final Endpoint<Void, ProjectTaskResponse> GET_PROJECT_TASK =
            Endpoint.createContract("/api/v1/tasks/{taskId}", HttpMethod.GET, Void.class, ProjectTaskResponse.class);
    public static final Endpoint<Void, AiRuntimeResponse> GET_RUNTIME =
            Endpoint.createContract("/api/v1/runtime", HttpMethod.GET, Void.class, AiRuntimeResponse.class);
    public static final Endpoint<Void, AgentListResponse[]> LIST_PROJECT_AGENTS =
            Endpoint.createContract("/api/v1/projects/{projectId}/agents", HttpMethod.GET, Void.class, AgentListResponse[].class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> LIST_PROJECT_AGENTS_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/agents", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<SaveAgentRequest, AgentResponse> CREATE_AGENT =
            Endpoint.createContract("/api/v1/projects/{projectId}/agents", HttpMethod.POST, SaveAgentRequest.class, AgentResponse.class);
    public static final Endpoint<SaveAgentRequest, ForgeAgentErrorResponse> CREATE_AGENT_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/agents", HttpMethod.POST, SaveAgentRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, AgentResponse> GET_AGENT =
            Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.GET, Void.class, AgentResponse.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> GET_AGENT_ERROR =
            Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<SaveAgentRequest, AgentResponse> UPDATE_AGENT =
            Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.PUT, SaveAgentRequest.class, AgentResponse.class);
    public static final Endpoint<SaveAgentRequest, ForgeAgentErrorResponse> UPDATE_AGENT_ERROR =
            Endpoint.createContract("/api/v1/agents/{agentId}", HttpMethod.PUT, SaveAgentRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, WorkflowResponse[]> LIST_PROJECT_WORKFLOWS =
            Endpoint.createContract("/api/v1/projects/{projectId}/workflows", HttpMethod.GET, Void.class, WorkflowResponse[].class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> LIST_PROJECT_WORKFLOWS_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/workflows", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<CreateWorkflowRequest, WorkflowResponse> CREATE_WORKFLOW =
            Endpoint.createContract("/api/v1/projects/{projectId}/workflows", HttpMethod.POST, CreateWorkflowRequest.class, WorkflowResponse.class);
    public static final Endpoint<CreateWorkflowRequest, ForgeAgentErrorResponse> CREATE_WORKFLOW_ERROR =
            Endpoint.createContract("/api/v1/projects/{projectId}/workflows", HttpMethod.POST, CreateWorkflowRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, WorkflowResponse> GET_WORKFLOW =
            Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.GET, Void.class, WorkflowResponse.class);
    public static final Endpoint<Void, ForgeAgentErrorResponse> GET_WORKFLOW_ERROR =
            Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.GET, Void.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<SaveWorkflowRequest, WorkflowResponse> UPDATE_WORKFLOW =
            Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.PUT, SaveWorkflowRequest.class, WorkflowResponse.class);
    public static final Endpoint<SaveWorkflowRequest, ForgeAgentErrorResponse> UPDATE_WORKFLOW_ERROR =
            Endpoint.createContract("/api/v1/workflows/{workflowId}", HttpMethod.PUT, SaveWorkflowRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<CreateWorkflowRunRequest, WorkflowRunResponse> CREATE_WORKFLOW_RUN =
            Endpoint.createContract("/api/v1/workflows/{workflowId}/runs", HttpMethod.POST, CreateWorkflowRunRequest.class, WorkflowRunResponse.class);
    public static final Endpoint<CreateWorkflowRunRequest, ForgeAgentErrorResponse> CREATE_WORKFLOW_RUN_ERROR =
            Endpoint.createContract("/api/v1/workflows/{workflowId}/runs", HttpMethod.POST, CreateWorkflowRunRequest.class, ForgeAgentErrorResponse.class);
    public static final Endpoint<Void, WorkflowRunSummaryResponse[]> LIST_WORKFLOW_RUNS =
            Endpoint.createContract("/api/v1/workflows/{workflowId}/runs", HttpMethod.GET, Void.class, WorkflowRunSummaryResponse[].class);
    public static final Endpoint<Void, WorkflowRunResponse> GET_WORKFLOW_RUN =
            Endpoint.createContract("/api/v1/workflow-runs/{runId}", HttpMethod.GET, Void.class, WorkflowRunResponse.class);

    private ForgeAgentMockMvcEndpoint() {
    }
}
