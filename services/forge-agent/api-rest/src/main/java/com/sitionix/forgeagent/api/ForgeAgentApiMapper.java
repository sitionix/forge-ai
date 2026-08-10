package com.sitionix.forgeagent.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRequest;
import com.sitionix.forgeagent.api.dto.NodeRunFailureResponse;
import com.sitionix.forgeagent.api.dto.NodeRunResponse;
import com.sitionix.forgeagent.api.dto.NodePositionResponse;
import com.sitionix.forgeagent.api.dto.NodeRequest;
import com.sitionix.forgeagent.api.dto.NodeResponse;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.api.dto.SaveWorkflowRequest;
import com.sitionix.forgeagent.api.dto.WorkflowRunResponse;
import com.sitionix.forgeagent.api.dto.WorkflowRunSummaryResponse;
import com.sitionix.forgeagent.api.dto.WorkflowResponse;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowRunCommand;
import com.sitionix.forgeagent.application.usecase.CreateProjectCommand;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.SaveAgentCommand;
import com.sitionix.forgeagent.application.usecase.SaveWorkflowCommand;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentListItem;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
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

    SaveAgentCommand toCommand(final SaveAgentRequest request) {
        final JsonNode outputSchema = request.outputSchema();
        if (outputSchema == null || !outputSchema.isObject()) {
            throw new ValidationException("INVALID_OUTPUT_SCHEMA", "Output schema must be a JSON object.");
        }
        try {
            return new SaveAgentCommand(
                    request.name(),
                    request.instructions(),
                    AgentOutputSchema.ofCanonicalJsonObject(this.objectMapper.writeValueAsString(outputSchema))
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
                        .toList()
        );
    }

    CreateWorkflowRunCommand toCommand(final CreateWorkflowRunRequest request) {
        return new CreateWorkflowRunCommand(request.input());
    }

    ProjectResponse toResponse(final Project project) {
        return new ProjectResponse(project.id(), project.name(), project.createdAt(), project.updatedAt());
    }

    AgentListResponse toResponse(final AgentListItem agent) {
        return new AgentListResponse(
                agent.id(),
                agent.projectId(),
                agent.name(),
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
                workflow.createdAt(),
                workflow.updatedAt()
        );
    }

    WorkflowRunSummaryResponse toSummaryResponse(final WorkflowRunSummary run) {
        return new WorkflowRunSummaryResponse(
                run.id(),
                run.sourceWorkflowId(),
                run.workflowName(),
                run.status(),
                run.createdAt(),
                run.startedAt(),
                run.finishedAt()
        );
    }

    WorkflowRunResponse toResponse(final WorkflowRun run) {
        return new WorkflowRunResponse(
                run.id(),
                run.projectId(),
                run.sourceWorkflowId(),
                run.workflowName(),
                run.input(),
                run.status(),
                run.nodeRuns().stream().map(this::toResponse).toList(),
                run.createdAt(),
                run.startedAt(),
                run.finishedAt()
        );
    }

    private Node toNode(final NodeRequest request) {
        final NodePosition position = request.position() == null
                ? new NodePosition(0.0, 0.0)
                : new NodePosition(request.position().x(), request.position().y());
        return new Node(
                request.id(),
                request.targetId(),
                request.dependsOnNodeIds() == null ? List.of() : request.dependsOnNodeIds(),
                position
        );
    }

    private NodeResponse toResponse(final Node node) {
        return new NodeResponse(
                node.id(),
                node.targetId(),
                node.dependsOnNodeIds(),
                new NodePositionResponse(node.position().x(), node.position().y())
        );
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
                    nodeRun.dependsOnNodeRunIds(),
                    new NodePositionResponse(nodeRun.position().x(), nodeRun.position().y()),
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
}
