package com.sitionix.forgeagent.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRequest;
import com.sitionix.forgeagent.api.dto.NodePositionResponse;
import com.sitionix.forgeagent.api.dto.NodeRequest;
import com.sitionix.forgeagent.api.dto.NodeResponse;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.api.dto.SaveWorkflowRequest;
import com.sitionix.forgeagent.api.dto.WorkflowResponse;
import com.sitionix.forgeagent.application.usecase.CreateProjectCommand;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.SaveAgentCommand;
import com.sitionix.forgeagent.application.usecase.SaveWorkflowCommand;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentListItem;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.Workflow;
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
}
