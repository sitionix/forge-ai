package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRequest;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.api.dto.SaveWorkflowRequest;
import com.sitionix.forgeagent.api.dto.WorkflowResponse;
import com.sitionix.forgeagent.application.usecase.AgentUseCases;
import com.sitionix.forgeagent.application.usecase.ProjectUseCases;
import com.sitionix.forgeagent.application.usecase.WorkflowUseCases;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ForgeAgentController {

    private final ProjectUseCases projectUseCases;
    private final AgentUseCases agentUseCases;
    private final WorkflowUseCases workflowUseCases;
    private final ForgeAgentApiMapper mapper;

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

    @PutMapping("/api/v1/workflows/{workflowId}")
    public ResponseEntity<WorkflowResponse> updateWorkflow(@PathVariable final UUID workflowId,
                                                           @Valid @RequestBody final SaveWorkflowRequest request) {
        return ResponseEntity.ok(this.mapper.toResponse(this.workflowUseCases.updateWorkflow(workflowId, this.mapper.toCommand(request))));
    }
}
