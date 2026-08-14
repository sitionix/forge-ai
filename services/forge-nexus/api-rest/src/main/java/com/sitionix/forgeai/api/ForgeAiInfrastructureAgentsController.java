package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.agentproxy.AgentDefinitionListResponse;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionRequest;
import com.sitionix.forgeai.api.agentproxy.AgentDefinitionResponse;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowRunResponse;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowRunSummaryResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectTaskPageResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectTaskResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRequest;
import com.sitionix.forgeai.api.agentproxy.AgentProjectResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProxyApiMapper;
import com.sitionix.forgeai.api.agentproxy.AgentRuntimeResponse;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowRequest;
import com.sitionix.forgeai.api.agentproxy.AgentWorkflowResponse;
import com.sitionix.forgeai.api.agentproxy.CreateAgentWorkflowRunRequest;
import com.sitionix.forgeai.api.agentproxy.CreateAgentProjectTaskRequest;
import com.sitionix.forgeai.api.agentproxy.SaveAgentWorkflowRequest;
import com.sitionix.forgeai.domain.usecase.CreateAgentDefinition;
import com.sitionix.forgeai.domain.usecase.CreateAgentProject;
import com.sitionix.forgeai.domain.usecase.CreateAgentProjectTask;
import com.sitionix.forgeai.domain.usecase.CreateAgentWorkflow;
import com.sitionix.forgeai.domain.usecase.CreateAgentWorkflowRun;
import com.sitionix.forgeai.domain.usecase.DeleteAgentDefinition;
import com.sitionix.forgeai.domain.usecase.DeleteAgentProject;
import com.sitionix.forgeai.domain.usecase.DeleteAgentProjectTask;
import com.sitionix.forgeai.domain.usecase.DeleteAgentWorkflow;
import com.sitionix.forgeai.domain.usecase.GetAgentDefinition;
import com.sitionix.forgeai.domain.usecase.GetAgentRuntime;
import com.sitionix.forgeai.domain.usecase.GetAgentWorkflow;
import com.sitionix.forgeai.domain.usecase.GetAgentWorkflowRun;
import com.sitionix.forgeai.domain.usecase.GetAgentProjectTask;
import com.sitionix.forgeai.domain.usecase.ListAgentProjects;
import com.sitionix.forgeai.domain.usecase.ListAgentProjectTasks;
import com.sitionix.forgeai.domain.usecase.ListAgentWorkflowRuns;
import com.sitionix.forgeai.domain.usecase.ListAgentWorkflows;
import com.sitionix.forgeai.domain.usecase.ListProjectAgentDefinitions;
import com.sitionix.forgeai.domain.usecase.UpdateAgentDefinition;
import com.sitionix.forgeai.domain.usecase.UpdateAgentWorkflow;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ForgeAiInfrastructureAgentsController {

    private final ListAgentProjects listAgentProjects;
    private final CreateAgentProject createAgentProject;
    private final DeleteAgentProject deleteAgentProject;
    private final CreateAgentProjectTask createAgentProjectTask;
    private final ListAgentProjectTasks listAgentProjectTasks;
    private final GetAgentProjectTask getAgentProjectTask;
    private final DeleteAgentProjectTask deleteAgentProjectTask;
    private final GetAgentRuntime getAgentRuntime;
    private final ListProjectAgentDefinitions listProjectAgentDefinitions;
    private final CreateAgentDefinition createAgentDefinition;
    private final GetAgentDefinition getAgentDefinition;
    private final UpdateAgentDefinition updateAgentDefinition;
    private final DeleteAgentDefinition deleteAgentDefinition;
    private final ListAgentWorkflows listAgentWorkflows;
    private final CreateAgentWorkflow createAgentWorkflow;
    private final GetAgentWorkflow getAgentWorkflow;
    private final UpdateAgentWorkflow updateAgentWorkflow;
    private final DeleteAgentWorkflow deleteAgentWorkflow;
    private final CreateAgentWorkflowRun createAgentWorkflowRun;
    private final ListAgentWorkflowRuns listAgentWorkflowRuns;
    private final GetAgentWorkflowRun getAgentWorkflowRun;
    private final AgentProxyApiMapper mapper;

    @GetMapping("/api/v1/infrastructure/agents/projects")
    public ResponseEntity<List<AgentProjectResponse>> listProjects() {
        return ResponseEntity.ok(this.listAgentProjects.execute().stream()
                .map(this.mapper::toResponse)
                .toList());
    }

    @PostMapping("/api/v1/infrastructure/agents/projects")
    public ResponseEntity<AgentProjectResponse> createProject(@Valid @RequestBody final AgentProjectRequest request) {
        final AgentProjectResponse response = this.mapper.toResponse(this.createAgentProject.execute(this.mapper.toCommand(request)));
        return ResponseEntity.created(URI.create("/api/v1/infrastructure/agents/projects/" + response.id())).body(response);
    }

    @DeleteMapping("/api/v1/infrastructure/agents/projects/{projectId}")
    public ResponseEntity<Void> deleteProject(@PathVariable final UUID projectId) {
        this.deleteAgentProject.execute(projectId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/infrastructure/agents/projects/{projectId}/tasks")
    public ResponseEntity<AgentProjectTaskResponse> createProjectTask(@PathVariable final UUID projectId,
                                                                      @Valid @RequestBody final CreateAgentProjectTaskRequest request) {
        final AgentProjectTaskResponse response = this.mapper.toResponse(
                this.createAgentProjectTask.execute(projectId, this.mapper.toCommand(request))
        );
        return ResponseEntity.created(URI.create("/api/v1/infrastructure/agents/tasks/" + response.id())).body(response);
    }

    @GetMapping("/api/v1/infrastructure/agents/projects/{projectId}/tasks")
    public ResponseEntity<AgentProjectTaskPageResponse> listProjectTasks(@PathVariable final UUID projectId,
                                                                         @RequestParam(defaultValue = "0") final int page,
                                                                         @RequestParam(defaultValue = "20") final int size) {
        return ResponseEntity.ok(this.mapper.toResponse(this.listAgentProjectTasks.execute(projectId, page, size)));
    }

    @GetMapping("/api/v1/infrastructure/agents/tasks/{taskId}")
    public ResponseEntity<AgentProjectTaskResponse> getProjectTask(@PathVariable final UUID taskId) {
        return ResponseEntity.ok(this.mapper.toResponse(this.getAgentProjectTask.execute(taskId)));
    }

    @DeleteMapping("/api/v1/infrastructure/agents/tasks/{taskId}")
    public ResponseEntity<Void> deleteProjectTask(@PathVariable final UUID taskId) {
        this.deleteAgentProjectTask.execute(taskId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/infrastructure/agents/runtime")
    public ResponseEntity<AgentRuntimeResponse> getRuntime() {
        return ResponseEntity.ok(this.mapper.toResponse(this.getAgentRuntime.execute()));
    }

    @GetMapping("/api/v1/infrastructure/agents/projects/{projectId}/agents")
    public ResponseEntity<List<AgentDefinitionListResponse>> listProjectAgents(@PathVariable final UUID projectId) {
        return ResponseEntity.ok(this.listProjectAgentDefinitions.execute(projectId).stream()
                .map(this.mapper::toResponse)
                .toList());
    }

    @PostMapping("/api/v1/infrastructure/agents/projects/{projectId}/agents")
    public ResponseEntity<AgentDefinitionResponse> createAgent(@PathVariable final UUID projectId,
                                                               @Valid @RequestBody final AgentDefinitionRequest request) {
        final AgentDefinitionResponse response = this.mapper.toResponse(
                this.createAgentDefinition.execute(projectId, this.mapper.toCommand(request))
        );
        return ResponseEntity.created(URI.create("/api/v1/infrastructure/agents/definitions/" + response.id())).body(response);
    }

    @GetMapping("/api/v1/infrastructure/agents/definitions/{agentId}")
    public ResponseEntity<AgentDefinitionResponse> getAgent(@PathVariable final UUID agentId) {
        return ResponseEntity.ok(this.mapper.toResponse(this.getAgentDefinition.execute(agentId)));
    }

    @DeleteMapping("/api/v1/infrastructure/agents/definitions/{agentId}")
    public ResponseEntity<Void> deleteAgent(@PathVariable final UUID agentId) {
        this.deleteAgentDefinition.execute(agentId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/infrastructure/agents/definitions/{agentId}")
    public ResponseEntity<AgentDefinitionResponse> updateAgent(@PathVariable final UUID agentId,
                                                               @Valid @RequestBody final AgentDefinitionRequest request) {
        return ResponseEntity.ok(this.mapper.toResponse(this.updateAgentDefinition.execute(agentId, this.mapper.toCommand(request))));
    }

    @GetMapping("/api/v1/infrastructure/agents/projects/{projectId}/workflows")
    public ResponseEntity<List<AgentWorkflowResponse>> listProjectWorkflows(@PathVariable final UUID projectId) {
        return ResponseEntity.ok(this.listAgentWorkflows.execute(projectId).stream()
                .map(this.mapper::toResponse)
                .toList());
    }

    @PostMapping("/api/v1/infrastructure/agents/projects/{projectId}/workflows")
    public ResponseEntity<AgentWorkflowResponse> createWorkflow(@PathVariable final UUID projectId,
                                                                @Valid @RequestBody final AgentWorkflowRequest request) {
        final AgentWorkflowResponse response = this.mapper.toResponse(
                this.createAgentWorkflow.execute(projectId, this.mapper.toCommand(request))
        );
        return ResponseEntity.created(URI.create("/api/v1/infrastructure/agents/workflows/" + response.id())).body(response);
    }

    @GetMapping("/api/v1/infrastructure/agents/workflows/{workflowId}")
    public ResponseEntity<AgentWorkflowResponse> getWorkflow(@PathVariable final UUID workflowId) {
        return ResponseEntity.ok(this.mapper.toResponse(this.getAgentWorkflow.execute(workflowId)));
    }

    @DeleteMapping("/api/v1/infrastructure/agents/workflows/{workflowId}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable final UUID workflowId) {
        this.deleteAgentWorkflow.execute(workflowId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/infrastructure/agents/workflows/{workflowId}")
    public ResponseEntity<AgentWorkflowResponse> updateWorkflow(@PathVariable final UUID workflowId,
                                                                @Valid @RequestBody final SaveAgentWorkflowRequest request) {
        return ResponseEntity.ok(this.mapper.toResponse(this.updateAgentWorkflow.execute(workflowId, this.mapper.toCommand(request))));
    }

    @PostMapping("/api/v1/infrastructure/agents/workflows/{workflowId}/runs")
    public ResponseEntity<AgentWorkflowRunResponse> createWorkflowRun(@PathVariable final UUID workflowId,
                                                                      @Valid @RequestBody final CreateAgentWorkflowRunRequest request) {
        final AgentWorkflowRunResponse response = this.mapper.toResponse(
                this.createAgentWorkflowRun.execute(workflowId, this.mapper.toCommand(request))
        );
        return ResponseEntity.created(URI.create("/api/v1/infrastructure/agents/workflow-runs/" + response.id())).body(response);
    }

    @GetMapping("/api/v1/infrastructure/agents/workflows/{workflowId}/runs")
    public ResponseEntity<List<AgentWorkflowRunSummaryResponse>> listWorkflowRuns(@PathVariable final UUID workflowId) {
        return ResponseEntity.ok(this.listAgentWorkflowRuns.execute(workflowId).stream()
                .map(this.mapper::toResponse)
                .toList());
    }

    @GetMapping("/api/v1/infrastructure/agents/workflow-runs/{runId}")
    public ResponseEntity<AgentWorkflowRunResponse> getWorkflowRun(@PathVariable final UUID runId) {
        return ResponseEntity.ok(this.mapper.toResponse(this.getAgentWorkflowRun.execute(runId)));
    }
}
