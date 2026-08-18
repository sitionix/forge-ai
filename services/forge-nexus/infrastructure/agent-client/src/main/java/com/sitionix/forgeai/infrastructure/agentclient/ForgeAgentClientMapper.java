package com.sitionix.forgeai.infrastructure.agentclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentConnectionResolution;
import com.sitionix.forgeai.domain.model.agentproxy.AgentModelSelection;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunFailure;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunOutputDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryConflictState;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryGitHead;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryGitHeadType;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryGitState;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryOperationState;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryUpstream;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryUpstreamRelation;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryWorkingTreeState;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskPage;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskSummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeEffort;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeModel;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeProvider;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRunConnection;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRunNode;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRunPort;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunExecutionEdge;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunGraph;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.ConnectionResolutionType;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.ImportAgentProjectRepositoryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.Node;
import com.sitionix.forgeai.domain.model.agentproxy.NodeInputMode;
import com.sitionix.forgeai.domain.model.agentproxy.NodePort;
import com.sitionix.forgeai.domain.model.agentproxy.NodePosition;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.WorkflowConnection;
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
import com.sitionix.forgeai.infrastructure.agentclient.dto.ConnectionResolutionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateProjectTaskRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ImportProjectRepositoryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeRunFailureResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeRunResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodePositionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodePositionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.SaveAgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskPageResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryGitHeadResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryGitStateResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryGitUpstreamResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskSummaryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RunConnectionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RunNodeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RunPortResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunGraphResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunExecutionEdgeResponse;
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

    CreateProjectTaskRequest toRequest(final CreateAgentProjectTaskCommand command) {
        return new CreateProjectTaskRequest(command.title(), command.input(), command.workflowId());
    }

    ImportProjectRepositoryRequest toRequest(final ImportAgentProjectRepositoryCommand command) {
        return new ImportProjectRepositoryRequest(command.remoteUrl());
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

    AgentProjectRepository toDomain(final ProjectRepositoryResponse response) {
        this.requireResponse(response, "repository");
        this.requireId(response.id(), "repository.id");
        this.requireId(response.projectId(), "repository.projectId");
        this.requireText(response.name(), "repository.name");
        return new AgentProjectRepository(
                response.id(),
                response.projectId(),
                response.name(),
                response.cloned(),
                this.toDomain(response.gitState()),
                response.createdAt()
        );
    }

    private AgentProjectRepositoryGitState toDomain(final ProjectRepositoryGitStateResponse response) {
        if (response == null) {
            return null;
        }
        if (!response.valid()) {
            return new AgentProjectRepositoryGitState(false, null, null, null, null, null, false, response.pullBlockedReason());
        }
        if (response.head() == null) {
            throw this.invalid("repository.gitState.head must not be null when valid is true");
        }
        if (response.workingTree() == null) {
            throw this.invalid("repository.gitState.workingTree must not be null when valid is true");
        }
        if (response.conflictState() == null) {
            throw this.invalid("repository.gitState.conflictState must not be null when valid is true");
        }
        if (response.operationState() == null) {
            throw this.invalid("repository.gitState.operationState must not be null when valid is true");
        }
        return new AgentProjectRepositoryGitState(
                true,
                this.toDomain(response.head()),
                this.toWorkingTree(response.workingTree()),
                this.toConflictState(response.conflictState()),
                this.toOperationState(response.operationState()),
                this.toDomain(response.upstream()),
                response.pullAllowed(),
                response.pullBlockedReason()
        );
    }

    private AgentProjectRepositoryGitHead toDomain(final ProjectRepositoryGitHeadResponse response) {
        if (response.type() == null) {
            throw this.invalid("repository.gitState.head.type must not be null");
        }
        return new AgentProjectRepositoryGitHead(this.toHeadType(response.type()), response.ref(), response.commit());
    }

    private AgentProjectRepositoryGitHeadType toHeadType(final String value) {
        try {
            return AgentProjectRepositoryGitHeadType.valueOf(value);
        } catch (final IllegalArgumentException exception) {
            throw this.invalid("repository.gitState.head.type is invalid");
        }
    }

    private AgentProjectRepositoryWorkingTreeState toWorkingTree(final String value) {
        try {
            return AgentProjectRepositoryWorkingTreeState.valueOf(value);
        } catch (final IllegalArgumentException exception) {
            throw this.invalid("repository.gitState.workingTree is invalid");
        }
    }

    private AgentProjectRepositoryConflictState toConflictState(final String value) {
        try {
            return AgentProjectRepositoryConflictState.valueOf(value);
        } catch (final IllegalArgumentException exception) {
            throw this.invalid("repository.gitState.conflictState is invalid");
        }
    }

    private AgentProjectRepositoryOperationState toOperationState(final String value) {
        try {
            return AgentProjectRepositoryOperationState.valueOf(value);
        } catch (final IllegalArgumentException exception) {
            throw this.invalid("repository.gitState.operationState is invalid");
        }
    }

    private AgentProjectRepositoryUpstream toDomain(final ProjectRepositoryGitUpstreamResponse response) {
        if (response == null) {
            return null;
        }
        if (response.ref() == null || response.ref().isBlank()) {
            throw this.invalid("repository.gitState.upstream.ref must not be blank");
        }
        if (response.relation() == null) {
            throw this.invalid("repository.gitState.upstream.relation must not be null");
        }
        try {
            return new AgentProjectRepositoryUpstream(response.ref(), AgentProjectRepositoryUpstreamRelation.valueOf(response.relation()));
        } catch (final IllegalArgumentException exception) {
            throw this.invalid("repository.gitState.upstream.relation is invalid");
        }
    }

    AgentProjectTaskSummary toDomain(final ProjectTaskSummaryResponse response) {
        this.requireResponse(response, "project task summary");
        this.requireId(response.id(), "task.id");
        this.requireId(response.projectId(), "task.projectId");
        this.requireText(response.title(), "task.title");
        this.requireId(response.workflowId(), "task.workflowId");
        this.requireText(response.workflowName(), "task.workflowName");
        this.requireId(response.latestWorkflowRunId(), "task.latestWorkflowRunId");
        if (response.executionStatus() == null) {
            throw this.invalid("task.executionStatus must not be null");
        }
        return new AgentProjectTaskSummary(
                response.id(),
                response.projectId(),
                response.title(),
                response.workflowId(),
                response.workflowName(),
                response.latestWorkflowRunId(),
                response.executionStatus(),
                response.createdAt(),
                response.updatedAt()
        );
    }

    AgentProjectTaskPage toDomain(final ProjectTaskPageResponse response) {
        this.requireResponse(response, "project task page");
        return new AgentProjectTaskPage(
                this.requireList(response.items(), "taskPage.items").stream()
                        .map(this::toDomain)
                        .toList(),
                response.page(),
                response.size(),
                response.totalItems(),
                response.totalPages()
        );
    }

    AgentProjectTask toDomain(final ProjectTaskResponse response) {
        this.requireResponse(response, "project task");
        this.requireId(response.id(), "task.id");
        this.requireId(response.projectId(), "task.projectId");
        this.requireText(response.title(), "task.title");
        this.requireText(response.input(), "task.input");
        this.requireId(response.workflowId(), "task.workflowId");
        return new AgentProjectTask(
                response.id(),
                response.projectId(),
                response.title(),
                response.input(),
                response.workflowId(),
                this.requireList(response.runs(), "task.runs").stream()
                        .map(this::toDomain)
                        .toList(),
                this.toOutputDocument(response.result()),
                response.createdAt(),
                response.updatedAt()
        );
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
                        .toList(),
                command.connections() == null ? List.of() : command.connections().stream()
                        .map(this::toRequest)
                        .toList(),
                command.taskInputPortId(),
                command.taskOutputPortId()
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
                this.requireList(response.connections(), "workflow.connections").stream()
                        .map(this::toDomain)
                        .toList(),
                response.taskInputPortId(),
                response.taskOutputPortId(),
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
                response.taskId(),
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
                response.taskId(),
                response.workflowName(),
                response.input(),
                response.status(),
                this.requireList(response.nodeRuns(), "workflowRun.nodeRuns").stream()
                        .map(this::toDomain)
                        .toList(),
                this.requireList(response.connectionResolutions(), "workflowRun.connectionResolutions").stream()
                        .map(this::toDomain)
                        .toList(),
                this.optionalList(response.executionEdges()).stream()
                        .map(this::toDomain)
                        .toList(),
                this.toDomain(response.runtimeGraph()),
                this.toOutputDocument(response.result()),
                response.resultSourceNodeRunId(),
                response.createdAt(),
                response.startedAt(),
                response.finishedAt()
        );
    }

    private AgentWorkflowRunGraph toDomain(final WorkflowRunGraphResponse response) {
        if (response == null) {
            return null;
        }
        return new AgentWorkflowRunGraph(
                response.taskInputPortId(),
                response.taskOutputPortId(),
                this.requireList(response.nodes(), "workflowRun.runtimeGraph.nodes").stream()
                        .map(this::toDomain)
                        .toList(),
                this.requireList(response.ports(), "workflowRun.runtimeGraph.ports").stream()
                        .map(this::toDomain)
                        .toList(),
                this.requireList(response.connections(), "workflowRun.runtimeGraph.connections").stream()
                        .map(this::toDomain)
                        .toList()
        );
    }

    private AgentRunNode toDomain(final RunNodeResponse response) {
        this.requireResponse(response, "runtime graph node");
        this.requireId(response.sourceNodeId(), "workflowRun.runtimeGraph.nodes.sourceNodeId");
        this.requireText(response.agentName(), "workflowRun.runtimeGraph.nodes.agentName");
        if (response.position() == null) {
            throw this.invalid("workflowRun.runtimeGraph.nodes.position must not be null");
        }
        return new AgentRunNode(
                response.sourceNodeId(),
                response.agentName(),
                new NodePosition(response.position().x(), response.position().y())
        );
    }

    private AgentRunPort toDomain(final RunPortResponse response) {
        this.requireResponse(response, "runtime graph port");
        this.requireId(response.sourcePortId(), "workflowRun.runtimeGraph.ports.sourcePortId");
        this.requireId(response.sourceNodeId(), "workflowRun.runtimeGraph.ports.sourceNodeId");
        this.requireText(response.direction(), "workflowRun.runtimeGraph.ports.direction");
        this.requireText(response.name(), "workflowRun.runtimeGraph.ports.name");
        return new AgentRunPort(
                response.sourcePortId(),
                response.sourceNodeId(),
                response.direction(),
                response.name(),
                response.order()
        );
    }

    private AgentRunConnection toDomain(final RunConnectionResponse response) {
        this.requireResponse(response, "runtime graph connection");
        this.requireId(response.sourceConnectionId(), "workflowRun.runtimeGraph.connections.sourceConnectionId");
        this.requireId(response.sourceOutputPortId(), "workflowRun.runtimeGraph.connections.sourceOutputPortId");
        this.requireId(response.targetInputPortId(), "workflowRun.runtimeGraph.connections.targetInputPortId");
        return new AgentRunConnection(
                response.sourceConnectionId(),
                response.sourceOutputPortId(),
                response.targetInputPortId()
        );
    }

    private AgentWorkflowRunExecutionEdge toDomain(final WorkflowRunExecutionEdgeResponse response) {
        this.requireResponse(response, "workflow run execution edge");
        this.requireId(response.sourceNodeRunId(), "workflowRun.executionEdges.sourceNodeRunId");
        this.requireId(response.targetNodeRunId(), "workflowRun.executionEdges.targetNodeRunId");
        this.requireText(response.sourceType(), "workflowRun.executionEdges.sourceType");
        return new AgentWorkflowRunExecutionEdge(
                response.sourceNodeRunId(),
                response.targetNodeRunId(),
                response.sourceType()
        );
    }

    private AgentNodeRunOutputDocument toOutputDocument(final com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null) {
            return null;
        }
        try {
            return new AgentNodeRunOutputDocument(this.objectMapper.writeValueAsString(value));
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Forge Agent response output was invalid.", exception);
        }
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
                inputMode(node.inputMode()).name(),
                node.inputs() == null ? List.of() : node.inputs().stream().map(this::toRequest).toList(),
                node.outputs() == null ? List.of() : node.outputs().stream().map(this::toRequest).toList(),
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
                inputMode(response.inputMode(), "node.inputMode"),
                response.inputs() == null ? List.of() : response.inputs().stream().map(this::toDomain).toList(),
                response.outputs() == null ? List.of() : response.outputs().stream().map(this::toDomain).toList(),
                new NodePosition(position.x(), position.y())
        );
    }

    private com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowConnectionRequest toRequest(final WorkflowConnection connection) {
        return new com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowConnectionRequest(
                connection.id(),
                connection.sourceOutputPortId(),
                connection.targetInputPortId()
        );
    }

    private WorkflowConnection toDomain(final com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowConnectionResponse response) {
        this.requireResponse(response, "workflow connection");
        this.requireId(response.id(), "connection.id");
        this.requireId(response.sourceOutputPortId(), "connection.sourceOutputPortId");
        this.requireId(response.targetInputPortId(), "connection.targetInputPortId");
        return new WorkflowConnection(response.id(), response.sourceOutputPortId(), response.targetInputPortId());
    }

    private com.sitionix.forgeai.infrastructure.agentclient.dto.NodePortRequest toRequest(final NodePort port) {
        return new com.sitionix.forgeai.infrastructure.agentclient.dto.NodePortRequest(
                port.id(),
                port.name(),
                port.description(),
                port.order()
        );
    }

    private NodePort toDomain(final com.sitionix.forgeai.infrastructure.agentclient.dto.NodePortResponse response) {
        this.requireResponse(response, "workflow node port");
        this.requireId(response.id(), "nodePort.id");
        this.requireText(response.name(), "nodePort.name");
        this.requireText(response.description(), "nodePort.description");
        return new NodePort(response.id(), response.name(), response.description(), response.order());
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
                    nodeRunInputMode(response.inputMode()),
                    new NodePosition(response.position().x(), response.position().y()),
                    response.executionFrameId(),
                    response.enteredViaInputPortId(),
                    response.activationFrameId(),
                    response.selectedOutputPortId(),
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

    private AgentConnectionResolution toDomain(final ConnectionResolutionResponse response) {
        this.requireResponse(response, "connection resolution");
        this.requireId(response.id(), "connectionResolution.id");
        this.requireId(response.executionFrameId(), "connectionResolution.executionFrameId");
        this.requireId(response.sourceNodeRunId(), "connectionResolution.sourceNodeRunId");
        this.requireId(response.sourceConnectionId(), "connectionResolution.sourceConnectionId");
        this.requireId(response.targetInputPortId(), "connectionResolution.targetInputPortId");
        if (response.resolutionType() == null) {
            throw this.invalid("connectionResolution.resolutionType must not be null");
        }
        try {
            return new AgentConnectionResolution(
                    response.id(),
                    response.executionFrameId(),
                    response.sourceNodeRunId(),
                    response.sourceConnectionId(),
                    response.targetInputPortId(),
                    ConnectionResolutionType.valueOf(response.resolutionType().name()),
                    response.payload() == null ? null : new AgentNodeRunOutputDocument(this.objectMapper.writeValueAsString(response.payload())),
                    response.consumedByNodeRunId(),
                    response.createdAt()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Forge Agent connection resolution payload was invalid.", exception);
        }
    }

    <T> List<T> requireList(final List<T> responses, final String field) {
        if (responses == null) {
            throw this.invalid(field + " must not be null");
        }
        return responses;
    }

    private <T> List<T> optionalList(final List<T> responses) {
        return responses == null ? List.of() : responses;
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

    private static NodeInputMode inputMode(final NodeInputMode inputMode) {
        return inputMode == null ? NodeInputMode.DEPENDENCIES_ONLY : inputMode;
    }

    private static NodeInputMode nodeRunInputMode(final String inputMode) {
        if (inputMode == null || inputMode.isBlank()) {
            return NodeInputMode.TASK_AND_DEPENDENCIES;
        }
        try {
            return NodeInputMode.valueOf(inputMode);
        } catch (final IllegalArgumentException exception) {
            throw new HttpMessageConversionException("Forge Agent response contract violation: nodeRun.inputMode is invalid", exception);
        }
    }

    private static NodeInputMode inputMode(final String inputMode, final String field) {
        if (inputMode == null || inputMode.isBlank()) {
            return NodeInputMode.DEPENDENCIES_ONLY;
        }
        try {
            return NodeInputMode.valueOf(inputMode);
        } catch (final IllegalArgumentException exception) {
            throw new HttpMessageConversionException("Forge Agent response contract violation: " + field + " is invalid", exception);
        }
    }
}
