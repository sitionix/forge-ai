package com.sitionix.forgeai.infrastructure.agentclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentModelSelection;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunFailure;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunOutputDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeEffort;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeModel;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeProvider;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.Node;
import com.sitionix.forgeai.domain.model.agentproxy.NodePosition;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentModelSelectionDto;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentRuntimeEffortResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentRuntimeModelResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentRuntimeProviderResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentRuntimeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeRunFailureResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeRunResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodePositionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodePositionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.SaveAgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunSummaryResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ForgeAgentClientMapper {

    private final ObjectMapper objectMapper;

    AgentProjectRequest toRequest(final CreateAgentProjectCommand command) {
        return new AgentProjectRequest(command.name());
    }

    AgentDefinitionRequest toRequest(final SaveAgentDefinitionCommand command) {
        try {
            return new AgentDefinitionRequest(
                    command.name(),
                    command.instructions(),
                    this.objectMapper.readTree(command.outputSchema().jsonObject()),
                    this.toDto(command.model())
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Output schema must be valid JSON.", exception);
        }
    }

    AgentProject toDomain(final AgentProjectResponse response) {
        this.requireResponse(response, "project");
        this.requireId(response.id(), "project.id");
        this.requireText(response.name(), "project.name");
        return new AgentProject(response.id(), response.name(), response.createdAt(), response.updatedAt());
    }

    AgentDefinitionListItem toDomain(final AgentDefinitionListResponse response) {
        this.requireResponse(response, "agent list item");
        this.requireId(response.id(), "agent.id");
        this.requireId(response.projectId(), "agent.projectId");
        this.requireText(response.name(), "agent.name");
        return new AgentDefinitionListItem(
                response.id(),
                response.projectId(),
                response.name(),
                this.toDomain(response.model()),
                response.createdAt(),
                response.updatedAt()
        );
    }

    AgentDefinitionDetails toDomain(final AgentDefinitionResponse response) {
        this.requireResponse(response, "agent");
        this.requireId(response.id(), "agent.id");
        this.requireId(response.projectId(), "agent.projectId");
        this.requireText(response.name(), "agent.name");
        this.requireText(response.instructions(), "agent.instructions");
        if (response.outputSchema() == null || !response.outputSchema().isObject()) {
            throw this.invalid("agent.outputSchema must be a JSON object");
        }
        try {
            return new AgentDefinitionDetails(
                    response.id(),
                    response.projectId(),
                    response.name(),
                    response.instructions(),
                    new AgentOutputSchemaDocument(this.objectMapper.writeValueAsString(response.outputSchema())),
                    this.toDomain(response.model()),
                    response.createdAt(),
                    response.updatedAt()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Forge Agent response output schema was invalid.", exception);
        }
    }

    AgentWorkflowRequest toRequest(final CreateAgentWorkflowCommand command) {
        return new AgentWorkflowRequest(command.name());
    }

    SaveAgentWorkflowRequest toRequest(final SaveAgentWorkflowCommand command) {
        return new SaveAgentWorkflowRequest(
                command.name(),
                command.nodes() == null ? List.of() : command.nodes().stream()
                        .map(this::toRequest)
                        .toList()
        );
    }

    CreateWorkflowRunRequest toRequest(final CreateAgentWorkflowRunCommand command) {
        return new CreateWorkflowRunRequest(command.input());
    }

    AgentWorkflow toDomain(final AgentWorkflowResponse response) {
        this.requireResponse(response, "workflow");
        this.requireId(response.id(), "workflow.id");
        this.requireId(response.projectId(), "workflow.projectId");
        this.requireText(response.name(), "workflow.name");
        return new AgentWorkflow(
                response.id(),
                response.projectId(),
                response.name(),
                this.requireList(response.nodes(), "workflow.nodes").stream()
                        .map(this::toDomain)
                        .toList(),
                response.createdAt(),
                response.updatedAt()
        );
    }

    AgentWorkflowRunSummary toDomain(final WorkflowRunSummaryResponse response) {
        this.requireResponse(response, "workflow run summary");
        this.requireId(response.id(), "workflowRun.id");
        this.requireId(response.sourceWorkflowId(), "workflowRun.sourceWorkflowId");
        this.requireText(response.workflowName(), "workflowRun.workflowName");
        if (response.status() == null) {
            throw this.invalid("workflowRun.status must not be null");
        }
        return new AgentWorkflowRunSummary(
                response.id(),
                response.sourceWorkflowId(),
                response.workflowName(),
                response.status(),
                response.createdAt(),
                response.startedAt(),
                response.finishedAt()
        );
    }

    AgentWorkflowRun toDomain(final WorkflowRunResponse response) {
        this.requireResponse(response, "workflow run");
        this.requireId(response.id(), "workflowRun.id");
        this.requireId(response.projectId(), "workflowRun.projectId");
        this.requireId(response.sourceWorkflowId(), "workflowRun.sourceWorkflowId");
        this.requireText(response.workflowName(), "workflowRun.workflowName");
        this.requireText(response.input(), "workflowRun.input");
        if (response.status() == null) {
            throw this.invalid("workflowRun.status must not be null");
        }
        return new AgentWorkflowRun(
                response.id(),
                response.projectId(),
                response.sourceWorkflowId(),
                response.workflowName(),
                response.input(),
                response.status(),
                this.requireList(response.nodeRuns(), "workflowRun.nodeRuns").stream()
                        .map(this::toDomain)
                        .toList(),
                response.createdAt(),
                response.startedAt(),
                response.finishedAt()
        );
    }

    AgentRuntimeCatalog toDomain(final AgentRuntimeResponse response) {
        this.requireResponse(response, "agent runtime");
        return new AgentRuntimeCatalog(this.requireList(response.providers(), "runtime.providers").stream()
                .map(this::toDomain)
                .toList());
    }

    private AgentRuntimeProvider toDomain(final AgentRuntimeProviderResponse response) {
        this.requireResponse(response, "runtime provider");
        this.requireText(response.providerId(), "runtime.providerId");
        this.requireText(response.displayName(), "runtime.displayName");
        if (response.status() == null) {
            throw this.invalid("runtime.status must not be null");
        }
        return new AgentRuntimeProvider(
                response.providerId(),
                response.displayName(),
                response.status(),
                response.version(),
                this.requireList(response.models(), "runtime.models").stream().map(this::toDomain).toList()
        );
    }

    private AgentRuntimeModel toDomain(final AgentRuntimeModelResponse response) {
        this.requireResponse(response, "runtime model");
        this.requireText(response.modelId(), "runtime.modelId");
        this.requireText(response.displayName(), "runtime.modelDisplayName");
        return new AgentRuntimeModel(
                response.modelId(),
                response.displayName(),
                response.description(),
                this.requireList(response.efforts(), "runtime.efforts").stream().map(this::toDomain).toList()
        );
    }

    private AgentRuntimeEffort toDomain(final AgentRuntimeEffortResponse response) {
        this.requireResponse(response, "runtime effort");
        this.requireText(response.effortId(), "runtime.effortId");
        this.requireText(response.description(), "runtime.effortDescription");
        return new AgentRuntimeEffort(response.effortId(), response.description());
    }

    private AgentModelSelectionDto toDto(final AgentModelSelection model) {
        if (model == null) {
            return null;
        }
        return new AgentModelSelectionDto(model.providerId(), model.modelId(), model.effortId());
    }

    private AgentModelSelection toDomain(final AgentModelSelectionDto model) {
        if (model == null) {
            return null;
        }
        return new AgentModelSelection(model.providerId(), model.modelId(), model.effortId());
    }

    private NodeRequest toRequest(final Node node) {
        return new NodeRequest(
                node.id(),
                node.targetId(),
                node.dependsOnNodeIds() == null ? List.of() : node.dependsOnNodeIds(),
                node.position() == null ? new NodePositionRequest(0.0, 0.0) : new NodePositionRequest(node.position().x(), node.position().y())
        );
    }

    private Node toDomain(final NodeResponse response) {
        this.requireResponse(response, "workflow node");
        this.requireId(response.id(), "node.id");
        this.requireId(response.targetId(), "node.targetId");
        final NodePositionResponse position = response.position();
        if (position == null) {
            throw this.invalid("node.position must not be null");
        }
        return new Node(
                response.id(),
                response.targetId(),
                this.requireList(response.dependsOnNodeIds(), "node.dependsOnNodeIds"),
                new NodePosition(position.x(), position.y())
        );
    }

    private AgentNodeRun toDomain(final NodeRunResponse response) {
        this.requireResponse(response, "node run");
        this.requireId(response.id(), "nodeRun.id");
        this.requireId(response.sourceNodeId(), "nodeRun.sourceNodeId");
        this.requireId(response.sourceAgentId(), "nodeRun.sourceAgentId");
        this.requireText(response.agentName(), "nodeRun.agentName");
        this.requireText(response.agentInstructions(), "nodeRun.agentInstructions");
        if (response.agentOutputSchema() == null || !response.agentOutputSchema().isObject()) {
            throw this.invalid("nodeRun.agentOutputSchema must be a JSON object");
        }
        if (response.position() == null) {
            throw this.invalid("nodeRun.position must not be null");
        }
        if (response.status() == null) {
            throw this.invalid("nodeRun.status must not be null");
        }
        try {
            return new AgentNodeRun(
                    response.id(),
                    response.sourceNodeId(),
                    response.sourceAgentId(),
                    response.agentName(),
                    response.agentInstructions(),
                    new AgentOutputSchemaDocument(this.objectMapper.writeValueAsString(response.agentOutputSchema())),
                    this.requireList(response.dependsOnNodeRunIds(), "nodeRun.dependsOnNodeRunIds"),
                    new NodePosition(response.position().x(), response.position().y()),
                    response.status(),
                    response.output() == null ? null : new AgentNodeRunOutputDocument(this.objectMapper.writeValueAsString(response.output())),
                    response.failure() == null ? null : this.toDomain(response.failure()),
                    response.createdAt(),
                    response.startedAt(),
                    response.finishedAt()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Forge Agent node run JSON was invalid.", exception);
        }
    }

    private AgentNodeRunFailure toDomain(final NodeRunFailureResponse response) {
        this.requireResponse(response, "node run failure");
        this.requireText(response.code(), "nodeRun.failure.code");
        this.requireText(response.message(), "nodeRun.failure.message");
        return new AgentNodeRunFailure(response.code(), response.message());
    }

    <T> List<T> requireList(final List<T> responses, final String field) {
        if (responses == null) {
            throw this.invalid(field + " must not be null");
        }
        return responses;
    }

    private void requireResponse(final Object response, final String label) {
        if (response == null) {
            throw this.invalid(label + " response must not be null");
        }
    }

    private void requireId(final UUID id, final String field) {
        if (id == null) {
            throw this.invalid(field + " must not be null");
        }
    }

    private void requireText(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw this.invalid(field + " must not be blank");
        }
    }

    private HttpMessageConversionException invalid(final String message) {
        return new HttpMessageConversionException("Forge Agent response contract violation: " + message);
    }
}
