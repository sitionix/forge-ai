package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.AiRuntimeResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.CreateProjectTaskRequest;
import com.sitionix.forgeagent.api.dto.ImportProjectRepositoryRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRequest;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.ProjectRepositoryResponse;
import com.sitionix.forgeagent.api.dto.ProjectTaskPageResponse;
import com.sitionix.forgeagent.api.dto.ProjectTaskResponse;
import com.sitionix.forgeagent.api.dto.ProjectTaskSummaryResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.api.dto.SaveWorkflowRequest;
import com.sitionix.forgeagent.api.dto.WorkflowRunResponse;
import com.sitionix.forgeagent.api.dto.WorkflowRunSummaryResponse;
import com.sitionix.forgeagent.api.dto.WorkflowResponse;
import com.sitionix.forgeagent.application.usecase.AgentUseCases;
import com.sitionix.forgeagent.application.usecase.GetAiRuntime;
import com.sitionix.forgeagent.application.usecase.ProjectRepositoryUseCases;
import com.sitionix.forgeagent.application.usecase.ProjectUseCases;
import com.sitionix.forgeagent.application.usecase.ProjectTaskUseCases;
import com.sitionix.forgeagent.application.usecase.WorkflowRunUseCases;
import com.sitionix.forgeagent.application.usecase.WorkflowUseCases;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ForgeAgentController {

    private final ProjectUseCases projectUseCases;
    private final ProjectRepositoryUseCases projectRepositoryUseCases;
    private final AgentUseCases agentUseCases;
    private final GetAiRuntime getAiRuntime;
    private final WorkflowUseCases workflowUseCases;
    private final WorkflowRunUseCases workflowRunUseCases;
    private final ProjectTaskUseCases projectTaskUseCases;
    private final ForgeAgentApiMapper mapper;

    @GetMapping("/api/v1/runtime")
    public ResponseEntity<AiRuntimeResponse> getRuntime() {
        return ResponseEntity.ok(this.mapper.toResponse(this.getAiRuntime.execute()));
    }

    @GetMapping("/api/v1/projects")
    public ResponseEntity<List<ProjectResponse>> listProjects() {
        return ResponseEntity.ok(this.projectUseCases.listProjects().stream()
                .map(this.mapper::toResponse)
                .toList());
    }

    @PostMapping("/api/v1/projects")
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody final CreateProjectRequest request) {
        final ProjectResponse response = this.mapper.toResponse(this.projectUseCases.createProject(this.mapper.toCommand(request)));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + response.id())).body(response);
    }

    @DeleteMapping("/api/v1/projects/{projectId}")
    public ResponseEntity<Void> deleteProject(@PathVariable final UUID projectId) {
        this.projectUseCases.deleteProject(projectId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/projects/{projectId}/repositories")
    public ResponseEntity<ProjectRepositoryResponse> importProjectRepository(@PathVariable final UUID projectId,
                                                                            @Valid @RequestBody final ImportProjectRepositoryRequest request) {
        final ProjectRepositoryResponse response = this.mapper.toResponse(
                this.projectRepositoryUseCases.importRepository(projectId, this.mapper.toCommand(request))
        );
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/repositories/" + response.id())).body(response);
    }

    @GetMapping("/api/v1/projects/{projectId}/repositories")
    public ResponseEntity<List<ProjectRepositoryResponse>> listProjectRepositories(@PathVariable final UUID projectId) {
        return ResponseEntity.ok(this.projectRepositoryUseCases.listProjectRepositories(projectId).stream()
                .map(this.mapper::toResponse)
                .toList());
    }

    @PostMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/clone")
    public ResponseEntity<ProjectRepositoryResponse> cloneProjectRepository(@PathVariable final UUID projectId,
                                                                           @PathVariable final UUID repositoryId) {
        return ResponseEntity.ok(this.mapper.toResponse(this.projectRepositoryUseCases.cloneRepository(projectId, repositoryId)));
    }

    @PostMapping("/api/v1/projects/{projectId}/tasks")
    public ResponseEntity<ProjectTaskResponse> createProjectTask(@PathVariable final UUID projectId,
                                                                 @Valid @RequestBody final CreateProjectTaskRequest request) {
        final ProjectTaskResponse response = this.mapper.toResponse(
                this.projectTaskUseCases.createProjectTask(projectId, this.mapper.toCommand(request))
        );
        return ResponseEntity.created(URI.create("/api/v1/tasks/" + response.id())).body(response);
    }

    @GetMapping("/api/v1/projects/{projectId}/tasks")
    public ResponseEntity<ProjectTaskPageResponse> listProjectTasks(@PathVariable final UUID projectId,
                                                                    @RequestParam(defaultValue = "0") final int page,
                                                                    @RequestParam(defaultValue = "20") final int size) {
        return ResponseEntity.ok(this.mapper.toResponse(this.projectTaskUseCases.listProjectTasks(projectId, page, size)));
    }

    @GetMapping("/api/v1/tasks/{taskId}")
    public ResponseEntity<ProjectTaskResponse> getProjectTask(@PathVariable final UUID taskId) {
        return ResponseEntity.ok(this.mapper.toResponse(this.projectTaskUseCases.getProjectTask(taskId)));
    }

    @DeleteMapping("/api/v1/tasks/{taskId}")
    public ResponseEntity<Void> deleteProjectTask(@PathVariable final UUID taskId) {
        this.projectTaskUseCases.deleteProjectTask(taskId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/projects/{projectId}/agents")
    public ResponseEntity<List<AgentListResponse>> listProjectAgents(@PathVariable final UUID projectId) {
        return ResponseEntity.ok(this.agentUseCases.listProjectAgents(projectId).stream()
                .map(this.mapper::toResponse)
                .toList());
    }

    @PostMapping("/api/v1/projects/{projectId}/agents")
    public ResponseEntity<AgentResponse> createAgent(@PathVariable final UUID projectId,
                                                     @Valid @RequestBody final SaveAgentRequest request) {
        final AgentResponse response = this.mapper.toResponse(this.agentUseCases.createAgent(projectId, this.mapper.toCommand(request)));
        return ResponseEntity.created(URI.create("/api/v1/agents/" + response.id())).body(response);
    }

    @GetMapping("/api/v1/agents/{agentId}")
    public ResponseEntity<AgentResponse> getAgent(@PathVariable final UUID agentId) {
        return ResponseEntity.ok(this.mapper.toResponse(this.agentUseCases.getAgent(agentId)));
    }

    @DeleteMapping("/api/v1/agents/{agentId}")
    public ResponseEntity<Void> deleteAgent(@PathVariable final UUID agentId) {
        this.agentUseCases.deleteAgent(agentId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/agents/{agentId}")
    public ResponseEntity<AgentResponse> updateAgent(@PathVariable final UUID agentId,
                                                     @Valid @RequestBody final SaveAgentRequest request) {
        return ResponseEntity.ok(this.mapper.toResponse(this.agentUseCases.updateAgent(agentId, this.mapper.toCommand(request))));
    }

    @GetMapping("/api/v1/projects/{projectId}/workflows")
    public ResponseEntity<List<WorkflowResponse>> listProjectWorkflows(@PathVariable final UUID projectId) {
        return ResponseEntity.ok(this.workflowUseCases.listProjectWorkflows(projectId).stream()
                .map(this.mapper::toResponse)
                .toList());
    }

    @PostMapping("/api/v1/projects/{projectId}/workflows")
    public ResponseEntity<WorkflowResponse> createWorkflow(@PathVariable final UUID projectId,
                                                           @Valid @RequestBody final CreateWorkflowRequest request) {
        final WorkflowResponse response = this.mapper.toResponse(this.workflowUseCases.createWorkflow(projectId, this.mapper.toCommand(request)));
        return ResponseEntity.created(URI.create("/api/v1/workflows/" + response.id())).body(response);
    }

    @GetMapping("/api/v1/workflows/{workflowId}")
    public ResponseEntity<WorkflowResponse> getWorkflow(@PathVariable final UUID workflowId) {
        return ResponseEntity.ok(this.mapper.toResponse(this.workflowUseCases.getWorkflow(workflowId)));
    }

    @DeleteMapping("/api/v1/workflows/{workflowId}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable final UUID workflowId) {
        this.workflowUseCases.deleteWorkflow(workflowId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/workflows/{workflowId}")
    public ResponseEntity<WorkflowResponse> updateWorkflow(@PathVariable final UUID workflowId,
                                                           @Valid @RequestBody final SaveWorkflowRequest request) {
        return ResponseEntity.ok(this.mapper.toResponse(this.workflowUseCases.updateWorkflow(workflowId, this.mapper.toCommand(request))));
    }

    @PostMapping("/api/v1/workflows/{workflowId}/runs")
    public ResponseEntity<WorkflowRunResponse> createWorkflowRun(@PathVariable final UUID workflowId,
                                                                 @Valid @RequestBody final CreateWorkflowRunRequest request) {
        final WorkflowRunResponse response = this.mapper.toResponse(
                this.workflowRunUseCases.createWorkflowRun(workflowId, this.mapper.toCommand(request))
        );
        return ResponseEntity.created(URI.create("/api/v1/workflow-runs/" + response.id())).body(response);
    }

    @GetMapping("/api/v1/workflows/{workflowId}/runs")
    public ResponseEntity<List<WorkflowRunSummaryResponse>> listWorkflowRuns(@PathVariable final UUID workflowId) {
        return ResponseEntity.ok(this.workflowRunUseCases.listWorkflowRuns(workflowId).stream()
                .map(this.mapper::toSummaryResponse)
                .toList());
    }

    @GetMapping("/api/v1/workflow-runs/{runId}")
    public ResponseEntity<WorkflowRunResponse> getWorkflowRun(@PathVariable final UUID runId) {
        return ResponseEntity.ok(this.mapper.toResponse(this.workflowRunUseCases.getWorkflowRun(runId)));
    }
}
