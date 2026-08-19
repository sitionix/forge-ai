package com.sitionix.forgeagent.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentModelSelectionRequest;
import com.sitionix.forgeagent.api.dto.AgentModelSelectionResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.AiRuntimeResponse;
import com.sitionix.forgeagent.api.dto.CodexRuntimeEffortResponse;
import com.sitionix.forgeagent.api.dto.CodexRuntimeModelResponse;
import com.sitionix.forgeagent.api.dto.CodexRuntimeProviderResponse;
import com.sitionix.forgeagent.api.dto.ConnectionResolutionResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.CreateProjectTaskRequest;
import com.sitionix.forgeagent.api.dto.ImportProjectRepositoryRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRequest;
import com.sitionix.forgeagent.api.dto.NodeRunFailureResponse;
import com.sitionix.forgeagent.api.dto.NodeRunResponse;
import com.sitionix.forgeagent.api.dto.NodePortRequest;
import com.sitionix.forgeagent.api.dto.NodePortResponse;
import com.sitionix.forgeagent.api.dto.NodePositionResponse;
import com.sitionix.forgeagent.api.dto.NodeRequest;
import com.sitionix.forgeagent.api.dto.NodeResponse;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.ProjectRepositoryGitStateResponse;
import com.sitionix.forgeagent.api.dto.ProjectRepositoryResponse;
import com.sitionix.forgeagent.api.dto.ProjectTaskPageResponse;
import com.sitionix.forgeagent.api.dto.ProjectTaskResponse;
import com.sitionix.forgeagent.api.dto.ProjectTaskSummaryResponse;
import com.sitionix.forgeagent.api.dto.RunConnectionResponse;
import com.sitionix.forgeagent.api.dto.RunNodeResponse;
import com.sitionix.forgeagent.api.dto.RunPortResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.api.dto.SaveWorkflowRequest;
import com.sitionix.forgeagent.api.dto.WorkflowRunExecutionEdgeResponse;
import com.sitionix.forgeagent.api.dto.WorkflowRunGraphResponse;
import com.sitionix.forgeagent.api.dto.WorkflowRunResponse;
import com.sitionix.forgeagent.api.dto.WorkflowRunSummaryResponse;
import com.sitionix.forgeagent.api.dto.WorkflowConnectionRequest;
import com.sitionix.forgeagent.api.dto.WorkflowConnectionResponse;
import com.sitionix.forgeagent.api.dto.WorkflowResponse;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowRunCommand;
import com.sitionix.forgeagent.application.usecase.CreateProjectCommand;
import com.sitionix.forgeagent.application.usecase.CreateProjectTaskCommand;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.ImportProjectRepositoryCommand;
import com.sitionix.forgeagent.application.usecase.SaveAgentCommand;
import com.sitionix.forgeagent.application.usecase.SaveWorkflowCommand;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentListItem;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.AiRuntimeCatalog;
import com.sitionix.forgeagent.domain.model.CodexRuntimeEffort;
import com.sitionix.forgeagent.domain.model.CodexRuntimeModel;
import com.sitionix.forgeagent.domain.model.CodexRuntimeProvider;
import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryView;
import com.sitionix.forgeagent.domain.model.ProjectTaskDetails;
import com.sitionix.forgeagent.domain.model.ProjectTaskSummaryPage;
import com.sitionix.forgeagent.domain.model.ProjectTaskSummary;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunExecutionEdge;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ForgeAgentApiMapper {

    private final ObjectMapper objectMapper;

    CreateProjectCommand toCommand(final CreateProjectRequest request) {
        return new CreateProjectCommand(request.name());
    }

    ImportProjectRepositoryCommand toCommand(final ImportProjectRepositoryRequest request) {
        return new ImportProjectRepositoryCommand(request.remoteUrl());
    }

    SaveAgentCommand toCommand(final SaveAgentRequest request) {
        final JsonNode outputSchema = request.outputSchema();
        if (outputSchema == null || !outputSchema.isObject()) {
            throw new ValidationException("INVALID_OUTPUT_SCHEMA", "Output schema must be a JSON object.");
        }
        try {
            return new SaveAgentCommand(
                    request.name(),
                    request.instructions(),
                    AgentOutputSchema.ofCanonicalJsonObject(this.objectMapper.writeValueAsString(outputSchema)),
                    this.toModelSelection(request.model())
            );
        } catch (final JsonProcessingException exception) {
            throw new ValidationException("INVALID_OUTPUT_SCHEMA", "Output schema must be valid JSON.");
        }
    }

    CreateWorkflowCommand toCommand(final CreateWorkflowRequest request) {
        return new CreateWorkflowCommand(request.name());
    }

    SaveWorkflowCommand toCommand(final SaveWorkflowRequest request) {
        return new SaveWorkflowCommand(
                request.name(),
                request.nodes() == null ? List.of() : request.nodes().stream()
                        .map(this::toNode)
                        .toList(),
                request.connections() == null ? List.of() : request.connections().stream()
                        .map(this::toConnection)
                        .toList(),
                request.taskInputPortId(),
                request.taskOutputPortId()
        );
    }

    CreateWorkflowRunCommand toCommand(final CreateWorkflowRunRequest request) {
        return new CreateWorkflowRunCommand(request.input());
    }

    CreateProjectTaskCommand toCommand(final CreateProjectTaskRequest request) {
        return new CreateProjectTaskCommand(request.title(), request.input(), request.workflowId(), request.repositoryIds());
    }

    ProjectResponse toResponse(final Project project) {
        return new ProjectResponse(project.id(), project.name(), project.createdAt(), project.updatedAt());
    }

    ProjectRepositoryResponse toResponse(final ProjectRepositoryView repository) {
        return new ProjectRepositoryResponse(
                repository.id(),
                repository.projectId(),
                repository.name(),
                repository.cloned(),
                this.toGitStateResponse(repository),
                repository.createdAt()
        );
    }

    private ProjectRepositoryGitStateResponse toGitStateResponse(final ProjectRepositoryView repository) {
        if (!repository.cloned() || repository.gitState() == null || !repository.gitState().valid()) {
            return null;
        }
        return new ProjectRepositoryGitStateResponse(
                repository.gitState().head().ref(),
                repository.gitState().workingTree().name(),
                repository.gitState().pullAvailable()
        );
    }

    AgentListResponse toResponse(final AgentListItem agent) {
        return new AgentListResponse(
                agent.id(),
                agent.projectId(),
                agent.name(),
                this.toResponse(agent.model()),
                agent.createdAt(),
                agent.updatedAt()
        );
    }

    AgentResponse toResponse(final AgentDetails agent) {
        try {
            return new AgentResponse(
                    agent.id(),
                    agent.projectId(),
                    agent.name(),
                    agent.instructions(),
                    this.objectMapper.readTree(agent.outputSchema().jsonObject()),
                    this.toResponse(agent.model()),
                    agent.createdAt(),
                    agent.updatedAt()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalStateException("Stored output schema is invalid JSON", exception);
        }
    }

    WorkflowResponse toResponse(final Workflow workflow) {
        return new WorkflowResponse(
                workflow.id(),
                workflow.projectId(),
                workflow.name(),
                workflow.nodes().stream().map(this::toResponse).toList(),
                workflow.connections().stream().map(this::toResponse).toList(),
                workflow.taskInputPortId(),
                workflow.taskOutputPortId(),
                workflow.createdAt(),
                workflow.updatedAt()
        );
    }

    WorkflowRunSummaryResponse toSummaryResponse(final WorkflowRunSummary run) {
        return new WorkflowRunSummaryResponse(
                run.id(),
                run.sourceWorkflowId(),
                run.taskId(),
                run.workflowName(),
                run.status(),
                run.createdAt(),
                run.startedAt(),
                run.finishedAt()
        );
    }

    WorkflowRunResponse toResponse(final WorkflowRun run) {
        try {
            return new WorkflowRunResponse(
                    run.id(),
                    run.projectId(),
                    run.sourceWorkflowId(),
                    run.taskId(),
                    run.workflowName(),
                    run.input(),
                    run.status(),
                    run.nodeRuns().stream().map(this::toResponse).toList(),
                    run.connectionResolutions().stream().map(this::toResponse).toList(),
                    run.executionEdges().stream().map(this::toResponse).toList(),
                    this.toResponse(run.runtimeGraph()),
                    run.result() == null ? null : this.objectMapper.readTree(run.result().jsonValue()),
                    run.resultSourceNodeRunId(),
                    run.createdAt(),
                    run.startedAt(),
                    run.finishedAt()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalStateException("Stored workflow run result JSON is invalid.", exception);
        }
    }

    private WorkflowRunGraphResponse toResponse(final com.sitionix.forgeagent.domain.model.WorkflowRunGraph graph) {
        if (graph == null) {
            return null;
        }
        return new WorkflowRunGraphResponse(
                graph.taskInputPortId(),
                graph.taskOutputPortId(),
                graph.nodes().stream().map(this::toResponse).toList(),
                graph.ports().stream().map(this::toResponse).toList(),
                graph.connections().stream().map(this::toResponse).toList()
        );
    }

    private RunNodeResponse toResponse(final RunNode node) {
        return new RunNodeResponse(
                node.sourceNodeId(),
                node.agentName(),
                new NodePositionResponse(node.position().x(), node.position().y())
        );
    }

    private RunPortResponse toResponse(final RunPort port) {
        return new RunPortResponse(
                port.sourcePortId(),
                port.sourceNodeId(),
                port.direction(),
                port.name(),
                port.order()
        );
    }

    private RunConnectionResponse toResponse(final RunConnection connection) {
        return new RunConnectionResponse(
                connection.sourceConnectionId(),
                connection.sourceOutputPortId(),
                connection.targetInputPortId()
        );
    }

    private WorkflowRunExecutionEdgeResponse toResponse(final WorkflowRunExecutionEdge edge) {
        return new WorkflowRunExecutionEdgeResponse(
                edge.sourceNodeRunId(),
                edge.targetNodeRunId(),
                edge.sourceType()
        );
    }

    ProjectTaskSummaryResponse toResponse(final ProjectTaskSummary task) {
        return new ProjectTaskSummaryResponse(
                task.id(),
                task.projectId(),
                task.title(),
                task.workflowId(),
                task.workflowName(),
                task.latestWorkflowRunId(),
                task.executionStatus(),
                task.createdAt(),
                task.updatedAt()
        );
    }

    ProjectTaskPageResponse toResponse(final ProjectTaskSummaryPage page) {
        return new ProjectTaskPageResponse(
                page.items().stream().map(this::toResponse).toList(),
                page.page(),
                page.size(),
                page.totalItems(),
                page.totalPages()
        );
    }

    ProjectTaskResponse toResponse(final ProjectTaskDetails task) {
        try {
            return new ProjectTaskResponse(
                    task.id(),
                    task.projectId(),
                    task.title(),
                    task.input(),
                    task.workflowId(),
                    task.repositoryIds(),
                    task.runs().stream().map(this::toSummaryResponse).toList(),
                    task.result() == null ? null : this.objectMapper.readTree(task.result().jsonValue()),
                    task.createdAt(),
                    task.updatedAt()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalStateException("Stored task result JSON is invalid.", exception);
        }
    }

    private Node toNode(final NodeRequest request) {
        final NodePosition position = request.position() == null
                ? new NodePosition(0.0, 0.0)
                : new NodePosition(request.position().x(), request.position().y());
        return new Node(
                request.id(),
                request.targetId(),
                inputMode(request.inputMode()),
                request.inputs() == null ? List.of() : request.inputs().stream().map(this::toNodePort).toList(),
                request.outputs() == null ? List.of() : request.outputs().stream().map(this::toNodePort).toList(),
                position
        );
    }

    private NodeResponse toResponse(final Node node) {
        return new NodeResponse(
                node.id(),
                node.targetId(),
                inputMode(node.inputMode()).name(),
                node.inputs() == null ? List.of() : node.inputs().stream().map(this::toResponse).toList(),
                node.outputs() == null ? List.of() : node.outputs().stream().map(this::toResponse).toList(),
                new NodePositionResponse(node.position().x(), node.position().y())
        );
    }

    private WorkflowConnection toConnection(final WorkflowConnectionRequest request) {
        if (request == null) {
            return null;
        }
        return new WorkflowConnection(request.id(), request.sourceOutputPortId(), request.targetInputPortId());
    }

    private WorkflowConnectionResponse toResponse(final WorkflowConnection connection) {
        return new WorkflowConnectionResponse(connection.id(), connection.sourceOutputPortId(), connection.targetInputPortId());
    }

    private NodePort toNodePort(final NodePortRequest request) {
        if (request == null) {
            return null;
        }
        return new NodePort(request.id(), request.name(), request.description(), request.order());
    }

    private NodePortResponse toResponse(final NodePort port) {
        return new NodePortResponse(port.id(), port.name(), port.description(), port.order());
    }

    private NodeRunResponse toResponse(final NodeRun nodeRun) {
        try {
            return new NodeRunResponse(
                    nodeRun.id(),
                    nodeRun.sourceNodeId(),
                    nodeRun.sourceAgentId(),
                    nodeRun.agentName(),
                    nodeRun.agentInstructions(),
                    this.objectMapper.readTree(nodeRun.agentOutputSchema().jsonObject()),
                    inputMode(nodeRun.inputMode()).name(),
                    new NodePositionResponse(nodeRun.position().x(), nodeRun.position().y()),
                    nodeRun.executionFrameId(),
                    nodeRun.enteredViaInputPortId(),
                    nodeRun.activationFrameId(),
                    nodeRun.selectedOutputPortId(),
                    nodeRun.status(),
                    nodeRun.output() == null ? null : this.objectMapper.readTree(nodeRun.output().jsonValue()),
                    nodeRun.failure() == null ? null : new NodeRunFailureResponse(nodeRun.failure().code(), nodeRun.failure().message()),
                    nodeRun.createdAt(),
                    nodeRun.startedAt(),
                    nodeRun.finishedAt()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalStateException("Stored node run JSON is invalid.", exception);
        }
    }

    private ConnectionResolutionResponse toResponse(final ConnectionResolution resolution) {
        try {
            return new ConnectionResolutionResponse(
                    resolution.id(),
                    resolution.executionFrameId(),
                    resolution.sourceNodeRunId(),
                    resolution.sourceConnectionId(),
                    resolution.targetInputPortId(),
                    resolution.type(),
                    resolution.payload() == null ? null : this.objectMapper.readTree(resolution.payload().jsonValue()),
                    resolution.consumedByNodeRunId(),
                    resolution.createdAt()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalStateException("Stored connection resolution JSON is invalid.", exception);
        }
    }

    AiRuntimeResponse toResponse(final AiRuntimeCatalog catalog) {
        return new AiRuntimeResponse(catalog.providers().stream().map(this::toResponse).toList());
    }

    private CodexRuntimeProviderResponse toResponse(final CodexRuntimeProvider provider) {
        return new CodexRuntimeProviderResponse(
                provider.providerId(),
                provider.displayName(),
                provider.status(),
                provider.version(),
                provider.models().stream().map(this::toResponse).toList()
        );
    }

    private CodexRuntimeModelResponse toResponse(final CodexRuntimeModel model) {
        return new CodexRuntimeModelResponse(
                model.modelId(),
                model.displayName(),
                model.description(),
                model.efforts().stream().map(this::toResponse).toList()
        );
    }

    private CodexRuntimeEffortResponse toResponse(final CodexRuntimeEffort effort) {
        return new CodexRuntimeEffortResponse(effort.effortId(), effort.description());
    }

    private AgentModelSelection toModelSelection(final AgentModelSelectionRequest request) {
        if (request == null) {
            return null;
        }
        return new AgentModelSelection(request.providerId(), request.modelId(), request.effortId());
    }

    private AgentModelSelectionResponse toResponse(final AgentModelSelection selection) {
        if (selection == null) {
            return null;
        }
        return new AgentModelSelectionResponse(selection.providerId(), selection.modelId(), selection.effortId());
    }

    private static NodeInputMode inputMode(final NodeInputMode inputMode) {
        return inputMode == null ? NodeInputMode.DEPENDENCIES_ONLY : inputMode;
    }

    private static NodeInputMode inputMode(final String inputMode) {
        if (inputMode == null || inputMode.isBlank()) {
            return NodeInputMode.DEPENDENCIES_ONLY;
        }
        try {
            return NodeInputMode.valueOf(inputMode);
        } catch (final IllegalArgumentException exception) {
            throw new ValidationException("INVALID_NODE_INPUT_MODE", "Workflow node input mode is invalid.");
        }
    }
}
