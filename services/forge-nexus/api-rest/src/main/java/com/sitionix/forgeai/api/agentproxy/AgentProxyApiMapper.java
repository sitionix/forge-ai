package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentModelSelection;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunFailure;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskPage;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskSummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeEffort;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeModel;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeProvider;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.Node;
import com.sitionix.forgeai.domain.model.agentproxy.NodeInputMode;
import com.sitionix.forgeai.domain.model.agentproxy.NodePosition;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentProxyApiMapper {

    private final ObjectMapper objectMapper;

    public CreateAgentProjectCommand toCommand(final AgentProjectRequest request) {
        return new CreateAgentProjectCommand(request.name());
    }

    public CreateAgentProjectTaskCommand toCommand(final CreateAgentProjectTaskRequest request) {
        return new CreateAgentProjectTaskCommand(request.title(), request.input(), request.workflowId());
    }

    public SaveAgentDefinitionCommand toCommand(final AgentDefinitionRequest request) {
        if (request.outputSchema() == null || !request.outputSchema().isObject()) {
            throw new IllegalArgumentException("Output schema must be a JSON object.");
        }
        try {
            return new SaveAgentDefinitionCommand(
                    request.name(),
                    request.instructions(),
                    new AgentOutputSchemaDocument(this.objectMapper.writeValueAsString(request.outputSchema())),
                    this.toDomain(request.model())
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Output schema must be valid JSON.", exception);
        }
    }

    public CreateAgentWorkflowCommand toCommand(final AgentWorkflowRequest request) {
        return new CreateAgentWorkflowCommand(request.name());
    }

    public SaveAgentWorkflowCommand toCommand(final SaveAgentWorkflowRequest request) {
        return new SaveAgentWorkflowCommand(
                request.name(),
                request.nodes() == null ? List.of() : request.nodes().stream()
                        .map(this::toDomain)
                        .toList()
        );
    }

    public CreateAgentWorkflowRunCommand toCommand(final CreateAgentWorkflowRunRequest request) {
        return new CreateAgentWorkflowRunCommand(request.input());
    }

    public AgentProjectResponse toResponse(final AgentProject project) {
        return new AgentProjectResponse(project.id(), project.name(), project.createdAt(), project.updatedAt());
    }

    public AgentProjectTaskSummaryResponse toResponse(final AgentProjectTaskSummary task) {
        return new AgentProjectTaskSummaryResponse(
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

    public AgentProjectTaskPageResponse toResponse(final AgentProjectTaskPage page) {
        return new AgentProjectTaskPageResponse(
                page.items().stream().map(this::toResponse).toList(),
                page.page(),
                page.size(),
                page.totalItems(),
                page.totalPages()
        );
    }

    public AgentProjectTaskResponse toResponse(final AgentProjectTask task) {
        return new AgentProjectTaskResponse(
                task.id(),
                task.projectId(),
                task.title(),
                task.input(),
                task.workflowId(),
                task.runs().stream().map(this::toResponse).toList(),
                task.createdAt(),
                task.updatedAt()
        );
    }

    public AgentDefinitionListResponse toResponse(final AgentDefinitionListItem agent) {
        return new AgentDefinitionListResponse(
                agent.id(),
                agent.projectId(),
                agent.name(),
                this.toResponse(agent.model()),
                agent.createdAt(),
                agent.updatedAt()
        );
    }

    public AgentDefinitionResponse toResponse(final AgentDefinitionDetails agent) {
        try {
            return new AgentDefinitionResponse(
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
            throw new IllegalStateException("Agent output schema is not valid JSON.", exception);
        }
    }

    public AgentRuntimeResponse toResponse(final AgentRuntimeCatalog runtime) {
        return new AgentRuntimeResponse(runtime.providers().stream().map(this::toResponse).toList());
    }

    private AgentRuntimeProviderResponse toResponse(final AgentRuntimeProvider provider) {
        return new AgentRuntimeProviderResponse(
                provider.providerId(),
                provider.displayName(),
                provider.status(),
                provider.version(),
                provider.models().stream().map(this::toResponse).toList()
        );
    }

    private AgentRuntimeModelResponse toResponse(final AgentRuntimeModel model) {
        return new AgentRuntimeModelResponse(
                model.modelId(),
                model.displayName(),
                model.description(),
                model.efforts().stream().map(this::toResponse).toList()
        );
    }

    private AgentRuntimeEffortResponse toResponse(final AgentRuntimeEffort effort) {
        return new AgentRuntimeEffortResponse(effort.effortId(), effort.description());
    }

    private AgentModelSelection toDomain(final AgentModelSelectionRequest request) {
        if (request == null) {
            return null;
        }
        return new AgentModelSelection(request.providerId(), request.modelId(), request.effortId());
    }

    private AgentModelSelectionResponse toResponse(final AgentModelSelection model) {
        if (model == null) {
            return null;
        }
        return new AgentModelSelectionResponse(model.providerId(), model.modelId(), model.effortId());
    }

    public AgentWorkflowResponse toResponse(final AgentWorkflow workflow) {
        return new AgentWorkflowResponse(
                workflow.id(),
                workflow.projectId(),
                workflow.name(),
                workflow.nodes().stream().map(this::toResponse).toList(),
                workflow.createdAt(),
                workflow.updatedAt()
        );
    }

    public AgentWorkflowRunSummaryResponse toResponse(final AgentWorkflowRunSummary run) {
        return new AgentWorkflowRunSummaryResponse(
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

    public AgentWorkflowRunResponse toResponse(final AgentWorkflowRun run) {
        return new AgentWorkflowRunResponse(
                run.id(),
                run.projectId(),
                run.sourceWorkflowId(),
                run.taskId(),
                run.workflowName(),
                run.input(),
                run.status(),
                run.nodeRuns().stream().map(this::toResponse).toList(),
                run.createdAt(),
                run.startedAt(),
                run.finishedAt()
        );
    }

    private Node toDomain(final NodeRequest request) {
        return new Node(
                request.id(),
                request.targetId(),
                request.dependsOnNodeIds() == null ? List.of() : request.dependsOnNodeIds(),
                inputMode(request.inputMode()),
                request.position() == null ? new NodePosition(0.0, 0.0) : new NodePosition(request.position().x(), request.position().y())
        );
    }

    private NodeResponse toResponse(final Node node) {
        return new NodeResponse(
                node.id(),
                node.targetId(),
                node.dependsOnNodeIds(),
                inputMode(node.inputMode()).name(),
                new NodePositionResponse(node.position().x(), node.position().y())
        );
    }

    private AgentNodeRunResponse toResponse(final AgentNodeRun nodeRun) {
        try {
            return new AgentNodeRunResponse(
                    nodeRun.id(),
                    nodeRun.sourceNodeId(),
                    nodeRun.sourceAgentId(),
                    nodeRun.agentName(),
                    nodeRun.agentInstructions(),
                    this.objectMapper.readTree(nodeRun.agentOutputSchema().jsonObject()),
                    nodeRun.dependsOnNodeRunIds(),
                    nodeRunInputMode(nodeRun.inputMode()).name(),
                    new NodePositionResponse(nodeRun.position().x(), nodeRun.position().y()),
                    nodeRun.status(),
                    nodeRun.output() == null ? null : this.objectMapper.readTree(nodeRun.output().jsonValue()),
                    nodeRun.failure() == null ? null : this.toResponse(nodeRun.failure()),
                    nodeRun.createdAt(),
                    nodeRun.startedAt(),
                    nodeRun.finishedAt()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalStateException("Agent workflow run JSON is not valid JSON.", exception);
        }
    }

    private AgentNodeRunFailureResponse toResponse(final AgentNodeRunFailure failure) {
        return new AgentNodeRunFailureResponse(failure.code(), failure.message());
    }

    private static NodeInputMode inputMode(final NodeInputMode inputMode) {
        return inputMode == null ? NodeInputMode.DEPENDENCIES_ONLY : inputMode;
    }

    private static NodeInputMode nodeRunInputMode(final NodeInputMode inputMode) {
        return inputMode == null ? NodeInputMode.TASK_AND_DEPENDENCIES : inputMode;
    }

    private static NodeInputMode inputMode(final String inputMode) {
        if (inputMode == null || inputMode.isBlank()) {
            return NodeInputMode.DEPENDENCIES_ONLY;
        }
        try {
            return NodeInputMode.valueOf(inputMode);
        } catch (final IllegalArgumentException exception) {
            throw new IllegalArgumentException("Workflow node input mode is invalid.", exception);
        }
    }
}
