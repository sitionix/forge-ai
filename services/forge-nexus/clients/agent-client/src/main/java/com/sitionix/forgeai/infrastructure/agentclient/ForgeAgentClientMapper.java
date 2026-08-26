package com.sitionix.forgeai.infrastructure.agentclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentConnectionResolution;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDockerLogConfiguration;
import com.sitionix.forgeai.domain.model.agentproxy.AgentFileLogConfiguration;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogDiscoveryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderConfiguration;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogSource;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogTargetCandidate;
import com.sitionix.forgeai.domain.model.agentproxy.AgentModelSelection;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunFailure;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunOutputDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryGitState;
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
import com.sitionix.forgeai.domain.model.agentproxy.AgentSshConnection;
import com.sitionix.forgeai.domain.model.agentproxy.AgentSystemdLogConfiguration;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunExecutionEdge;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunGraph;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.ConnectionResolutionType;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentSshConnectionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.ImportAgentProjectRepositoryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.Node;
import com.sitionix.forgeai.domain.model.agentproxy.NodePort;
import com.sitionix.forgeai.domain.model.agentproxy.NodePosition;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentLogSourceCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.WorkflowConnection;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogDiscoveryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogSourceRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogSourceResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogTargetCandidateResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentModelSelectionDto;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentRuntimeEffortResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentRuntimeModelResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentRuntimeProviderResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentRuntimeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentSshConnectionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentSshConnectionResponse;
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
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryGitStateResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ForgeAgentClientMapper {

    private final ObjectMapper objectMapper;

    AgentLogSourceRequest toRequest(final SaveAgentLogSourceCommand command) {
        return new AgentLogSourceRequest(
                command.name(),
                command.serviceId(),
                command.connection(),
                command.sshConnectionId(),
                command.provider(),
                command.container(),
                command.composeService(),
                command.composeFile(),
                command.systemdMode(),
                command.unit(),
                command.path(),
                command.enabled());
    }

    AgentLogDiscoveryRequest toRequest(final AgentLogDiscoveryCommand command) {
        return new AgentLogDiscoveryRequest(
                command.connection(),
                command.sshConnectionId(),
                command.provider(),
                command.repositoryId());
    }

    AgentSshConnectionRequest toRequest(final CreateAgentSshConnectionCommand command) {
        return new AgentSshConnectionRequest(
                command.name(),
                command.host(),
                command.port(),
                command.username(),
                command.authType(),
                command.privateKeyPath(),
                command.password());
    }

    AgentLogSource toDomain(final AgentLogSourceResponse response) {
        final AgentLogProviderConfiguration configuration =
                switch (response.provider()) {
                    case DOCKER ->
                            new AgentDockerLogConfiguration(
                                    response.configuration().container(),
                                    response.configuration().composeService(),
                                    response.configuration().composeFile());
                    case SYSTEMD -> new AgentSystemdLogConfiguration(
                            response.configuration().systemdMode(), response.configuration().unit());
                    case FILE -> new AgentFileLogConfiguration(response.configuration().path());
                };
        return new AgentLogSource(
                response.id(),
                response.projectId(),
                response.name(),
                response.serviceId(),
                response.connection(),
                response.sshConnectionId(),
                response.provider(),
                configuration,
                response.enabled(),
                response.createdAt(),
                response.updatedAt());
    }

    AgentLogTargetCandidate toDomain(final AgentLogTargetCandidateResponse response) {
        return new AgentLogTargetCandidate(
                response.id(),
                response.label(),
                response.status(),
                response.image(),
                response.composeProject(),
                response.composeService(),
                response.composeFile(),
                response.suggested());
    }

    AgentSshConnection toDomain(final AgentSshConnectionResponse response) {
        return new AgentSshConnection(
                response.id(),
                response.projectId(),
                response.name(),
                response.host(),
                response.port(),
                response.username(),
                response.authType(),
                response.createdAt(),
                response.updatedAt());
    }

    AgentProjectRequest toRequest(final CreateAgentProjectCommand command) {
        return new AgentProjectRequest(command.name());
    }

    CreateProjectTaskRequest toRequest(final CreateAgentProjectTaskCommand command) {
        return new CreateProjectTaskRequest(command.title(), command.input(), command.workflowId(), command.repositoryIds());
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
        return new AgentProject(response.id(), response.name(), response.createdAt(), response.updatedAt());
    }

    AgentProjectRepository toDomain(final ProjectRepositoryResponse response) {
        return new AgentProjectRepository(
                response.id(),
                response.projectId(),
                response.name(),
                response.cloned(),
                this.toDomain(response.git()),
                response.createdAt()
        );
    }

    private AgentProjectRepositoryGitState toDomain(final ProjectRepositoryGitStateResponse response) {
        if (response == null) {
            return null;
        }
        return new AgentProjectRepositoryGitState(
                response.branch(),
                response.workingTree(),
                response.pullAvailable()
        );
    }

    AgentProjectTaskSummary toDomain(final ProjectTaskSummaryResponse response) {
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
        return new AgentProjectTaskPage(
                response.items() == null ? null : response.items().stream()
                        .map(this::toDomain)
                        .toList(),
                response.page(),
                response.size(),
                response.totalItems(),
                response.totalPages()
        );
    }

    AgentProjectTask toDomain(final ProjectTaskResponse response) {
        return new AgentProjectTask(
                response.id(),
                response.projectId(),
                response.title(),
                response.input(),
                response.workflowId(),
                response.repositoryIds(),
                response.runs() == null ? null : response.runs().stream()
                        .map(this::toDomain)
                        .toList(),
                this.toOutputDocument(response.result()),
                response.createdAt(),
                response.updatedAt()
        );
    }

    AgentDefinitionListItem toDomain(final AgentDefinitionListResponse response) {
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
        try {
            return new AgentDefinitionDetails(
                    response.id(),
                    response.projectId(),
                    response.name(),
                    response.instructions(),
                    response.outputSchema() == null ? null
                            : new AgentOutputSchemaDocument(this.objectMapper.writeValueAsString(response.outputSchema())),
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
                command.nodes() == null ? null : command.nodes().stream()
                        .map(this::toRequest)
                        .toList(),
                command.connections() == null ? null : command.connections().stream()
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
        return new AgentWorkflow(
                response.id(),
                response.projectId(),
                response.name(),
                response.nodes() == null ? null : response.nodes().stream()
                        .map(this::toDomain)
                        .toList(),
                response.connections() == null ? null : response.connections().stream()
                        .map(this::toDomain)
                        .toList(),
                response.taskInputPortId(),
                response.taskOutputPortId(),
                response.createdAt(),
                response.updatedAt()
        );
    }

    AgentWorkflowRunSummary toDomain(final WorkflowRunSummaryResponse response) {
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
        return new AgentWorkflowRun(
                response.id(),
                response.projectId(),
                response.sourceWorkflowId(),
                response.taskId(),
                response.workflowName(),
                response.input(),
                response.status(),
                response.nodeRuns() == null ? null : response.nodeRuns().stream()
                        .map(this::toDomain)
                        .toList(),
                response.connectionResolutions() == null ? null : response.connectionResolutions().stream()
                        .map(this::toDomain)
                        .toList(),
                response.executionEdges() == null ? null : response.executionEdges().stream()
                        .map(this::toDomain)
                        .toList(),
                this.toDomain(response.runtimeGraph()),
                this.toOutputDocument(response.result()),
                response.resultSourceNodeRunId(),
                response.createdAt(),
                response.startedAt(),
                response.finishedAt(),
                response.repositoryIds()
        );
    }

    private AgentWorkflowRunGraph toDomain(final WorkflowRunGraphResponse response) {
        if (response == null) {
            return null;
        }
        return new AgentWorkflowRunGraph(
                response.taskInputPortId(),
                response.taskOutputPortId(),
                response.nodes() == null ? null : response.nodes().stream()
                        .map(this::toDomain)
                        .toList(),
                response.ports() == null ? null : response.ports().stream()
                        .map(this::toDomain)
                        .toList(),
                response.connections() == null ? null : response.connections().stream()
                        .map(this::toDomain)
                        .toList()
        );
    }

    private AgentRunNode toDomain(final RunNodeResponse response) {
        return new AgentRunNode(
                response.sourceNodeId(),
                response.agentName(),
                response.position() == null ? null : new NodePosition(response.position().x(), response.position().y()),
                response.scopeMode()
        );
    }

    private AgentRunPort toDomain(final RunPortResponse response) {
        return new AgentRunPort(
                response.sourcePortId(),
                response.sourceNodeId(),
                response.direction(),
                response.name(),
                response.order()
        );
    }

    private AgentRunConnection toDomain(final RunConnectionResponse response) {
        return new AgentRunConnection(
                response.sourceConnectionId(),
                response.sourceOutputPortId(),
                response.targetInputPortId()
        );
    }

    private AgentWorkflowRunExecutionEdge toDomain(final WorkflowRunExecutionEdgeResponse response) {
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
        return new AgentRuntimeCatalog(response.providers() == null ? null : response.providers().stream()
                .map(this::toDomain)
                .toList());
    }

    private AgentRuntimeProvider toDomain(final AgentRuntimeProviderResponse response) {
        return new AgentRuntimeProvider(
                response.providerId(),
                response.displayName(),
                response.status(),
                response.version(),
                response.models() == null ? null : response.models().stream().map(this::toDomain).toList()
        );
    }

    private AgentRuntimeModel toDomain(final AgentRuntimeModelResponse response) {
        return new AgentRuntimeModel(
                response.modelId(),
                response.displayName(),
                response.description(),
                response.efforts() == null ? null : response.efforts().stream().map(this::toDomain).toList()
        );
    }

    private AgentRuntimeEffort toDomain(final AgentRuntimeEffortResponse response) {
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
                node.inputMode(),
                node.inputs() == null ? null : node.inputs().stream().map(this::toRequest).toList(),
                node.outputs() == null ? null : node.outputs().stream().map(this::toRequest).toList(),
                node.position() == null ? null : new NodePositionRequest(node.position().x(), node.position().y()),
                node.scopeMode()
        );
    }

    private Node toDomain(final NodeResponse response) {
        final NodePositionResponse position = response.position();
        return new Node(
                response.id(),
                response.targetId(),
                response.inputMode(),
                response.inputs() == null ? null : response.inputs().stream().map(this::toDomain).toList(),
                response.outputs() == null ? null : response.outputs().stream().map(this::toDomain).toList(),
                position == null ? null : new NodePosition(position.x(), position.y()),
                response.scopeMode()
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
        return new NodePort(response.id(), response.name(), response.description(), response.order());
    }

    private AgentNodeRun toDomain(final NodeRunResponse response) {
        try {
            return new AgentNodeRun(
                    response.id(),
                    response.sourceNodeId(),
                    response.sourceAgentId(),
                    response.agentName(),
                    response.agentInstructions(),
                    response.agentOutputSchema() == null ? null
                            : new AgentOutputSchemaDocument(this.objectMapper.writeValueAsString(response.agentOutputSchema())),
                    response.inputMode(),
                    response.position() == null ? null
                            : new NodePosition(response.position().x(), response.position().y()),
                    response.executionFrameId(),
                    response.enteredViaInputPortId(),
                    response.activationFrameId(),
                    response.selectedOutputPortId(),
                    response.status(),
                    response.output() == null ? null : new AgentNodeRunOutputDocument(this.objectMapper.writeValueAsString(response.output())),
                    response.failure() == null ? null : this.toDomain(response.failure()),
                    response.createdAt(),
                    response.startedAt(),
                    response.finishedAt(),
                    response.repositoryId()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Forge Agent node run JSON was invalid.", exception);
        }
    }

    private AgentNodeRunFailure toDomain(final NodeRunFailureResponse response) {
        return new AgentNodeRunFailure(response.code(), response.message());
    }

    private AgentConnectionResolution toDomain(final ConnectionResolutionResponse response) {
        try {
            return new AgentConnectionResolution(
                    response.id(),
                    response.executionFrameId(),
                    response.sourceNodeRunId(),
                    response.sourceConnectionId(),
                    response.targetInputPortId(),
                    response.resolutionType() == null ? null
                            : ConnectionResolutionType.valueOf(response.resolutionType().name()),
                    response.payload() == null ? null : new AgentNodeRunOutputDocument(this.objectMapper.writeValueAsString(response.payload())),
                    response.consumedByNodeRunId(),
                    response.createdAt(),
                    response.targetRepositoryId()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Forge Agent connection resolution payload was invalid.", exception);
        }
    }

}
