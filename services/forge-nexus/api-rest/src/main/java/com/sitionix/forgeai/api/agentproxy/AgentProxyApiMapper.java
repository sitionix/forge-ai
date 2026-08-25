package com.sitionix.forgeai.api.agentproxy;

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
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunExecutionEdge;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunGraph;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.ImportAgentProjectRepositoryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.Node;
import com.sitionix.forgeai.domain.model.agentproxy.NodePort;
import com.sitionix.forgeai.domain.model.agentproxy.NodePosition;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.WorkflowConnection;
import java.util.List;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDockerLogConfiguration;
import com.sitionix.forgeai.domain.model.agentproxy.AgentFileLogConfiguration;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogDiscoveryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderConfiguration;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogSource;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogTargetCandidate;
import com.sitionix.forgeai.domain.model.agentproxy.AgentSshConnection;
import com.sitionix.forgeai.domain.model.agentproxy.AgentSystemdLogConfiguration;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentSshConnectionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentLogSourceCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentProxyApiMapper {

    private final ObjectMapper objectMapper;

    public SaveAgentLogSourceCommand toCommand(final AgentLogSourceRequest request) {
      return new SaveAgentLogSourceCommand(
          request.name(),
          request.serviceId(),
          request.connection(),
          request.sshConnectionId(),
          request.provider(),
          request.container(),
          request.composeService(),
          request.composeFile(),
          request.unit(),
          request.path(),
          request.enabled());
    }

    public AgentLogDiscoveryCommand toCommand(final AgentLogDiscoveryRequest request) {
      return new AgentLogDiscoveryCommand(
          request.connection(),
          request.sshConnectionId(),
          request.provider(),
          request.repositoryId());
    }

    public CreateAgentSshConnectionCommand toCommand(final AgentSshConnectionRequest request) {
      return new CreateAgentSshConnectionCommand(
          request.name(),
          request.host(),
          request.port(),
          request.username(),
          request.privateKeyPath());
    }

    public AgentLogSourceResponse toResponse(final AgentLogSource source) {
      return new AgentLogSourceResponse(
          source.id(),
          source.projectId(),
          source.name(),
          source.serviceId(),
          source.connection(),
          source.sshConnectionId(),
          source.provider(),
          this.toResponse(source.configuration()),
          source.enabled(),
          source.createdAt(),
          source.updatedAt());
    }

    public AgentLogTargetCandidateResponse toResponse(final AgentLogTargetCandidate candidate) {
      return new AgentLogTargetCandidateResponse(
          candidate.id(),
          candidate.label(),
          candidate.status(),
          candidate.image(),
          candidate.composeProject(),
          candidate.composeService(),
          candidate.composeFile(),
          candidate.suggested());
    }

    private AgentLogConfigurationResponse toResponse(
        final AgentLogProviderConfiguration configuration) {
      return switch (configuration) {
        case AgentDockerLogConfiguration docker ->
            new AgentLogConfigurationResponse(
                docker.container(), docker.composeService(), docker.composeFile(), null, null);
        case AgentSystemdLogConfiguration systemd ->
            new AgentLogConfigurationResponse(null, null, null, systemd.unit(), null);
        case AgentFileLogConfiguration file ->
            new AgentLogConfigurationResponse(null, null, null, null, file.path());
      };
    }

    public AgentSshConnectionResponse toResponse(final AgentSshConnection connection) {
      return new AgentSshConnectionResponse(
          connection.id(),
          connection.projectId(),
          connection.name(),
          connection.host(),
          connection.port(),
          connection.username(),
          connection.createdAt(),
          connection.updatedAt());
    }

    public CreateAgentProjectCommand toCommand(final AgentProjectRequest request) {
        return new CreateAgentProjectCommand(request.name());
    }

    public CreateAgentProjectTaskCommand toCommand(final CreateAgentProjectTaskRequest request) {
        return new CreateAgentProjectTaskCommand(request.title(), request.input(), request.workflowId(), request.repositoryIds());
    }

    public ImportAgentProjectRepositoryCommand toCommand(final ImportAgentProjectRepositoryRequest request) {
        return new ImportAgentProjectRepositoryCommand(request.remoteUrl());
    }

    public SaveAgentDefinitionCommand toCommand(final AgentDefinitionRequest request) {
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
                request.nodes() == null ? null : request.nodes().stream()
                        .map(this::toDomain)
                        .toList(),
                request.connections() == null ? null : request.connections().stream()
                        .map(this::toDomain)
                        .toList(),
                request.taskInputPortId(),
                request.taskOutputPortId()
        );
    }

    public CreateAgentWorkflowRunCommand toCommand(final CreateAgentWorkflowRunRequest request) {
        return new CreateAgentWorkflowRunCommand(request.input());
    }

    public AgentProjectResponse toResponse(final AgentProject project) {
        return new AgentProjectResponse(project.id(), project.name(), project.createdAt(), project.updatedAt());
    }

    public AgentProjectRepositoryResponse toResponse(final AgentProjectRepository repository) {
        return new AgentProjectRepositoryResponse(
                repository.id(),
                repository.projectId(),
                repository.name(),
                repository.cloned(),
                this.toResponse(repository.git()),
                repository.createdAt()
        );
    }

    private AgentProjectRepositoryGitStateResponse toResponse(final AgentProjectRepositoryGitState gitState) {
        if (gitState == null) {
            return null;
        }
        return new AgentProjectRepositoryGitStateResponse(
                gitState.branch(),
                gitState.workingTree(),
                gitState.pullAvailable()
        );
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
                page.items() == null ? null : page.items().stream().map(this::toResponse).toList(),
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
                task.repositoryIds(),
                task.runs() == null ? null : task.runs().stream().map(this::toResponse).toList(),
                this.toJsonNode(task.result()),
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
        return new AgentRuntimeResponse(runtime.providers() == null ? null
                : runtime.providers().stream().map(this::toResponse).toList());
    }

    private AgentRuntimeProviderResponse toResponse(final AgentRuntimeProvider provider) {
        return new AgentRuntimeProviderResponse(
                provider.providerId(),
                provider.displayName(),
                provider.status(),
                provider.version(),
                provider.models() == null ? null : provider.models().stream().map(this::toResponse).toList()
        );
    }

    private AgentRuntimeModelResponse toResponse(final AgentRuntimeModel model) {
        return new AgentRuntimeModelResponse(
                model.modelId(),
                model.displayName(),
                model.description(),
                model.efforts() == null ? null : model.efforts().stream().map(this::toResponse).toList()
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
                workflow.nodes() == null ? null : workflow.nodes().stream().map(this::toResponse).toList(),
                workflow.connections() == null ? null : workflow.connections().stream().map(this::toResponse).toList(),
                workflow.taskInputPortId(),
                workflow.taskOutputPortId(),
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
                run.nodeRuns() == null ? null : run.nodeRuns().stream().map(this::toResponse).toList(),
                run.connectionResolutions() == null ? null : run.connectionResolutions().stream().map(this::toResponse).toList(),
                run.executionEdges() == null ? null : run.executionEdges().stream().map(this::toResponse).toList(),
                this.toResponse(run.runtimeGraph()),
                this.toJsonNode(run.result()),
                run.resultSourceNodeRunId(),
                run.createdAt(),
                run.startedAt(),
                run.finishedAt(),
                run.repositoryIds()
        );
    }

    private AgentWorkflowRunGraphResponse toResponse(final AgentWorkflowRunGraph graph) {
        if (graph == null) {
            return null;
        }
        return new AgentWorkflowRunGraphResponse(
                graph.taskInputPortId(),
                graph.taskOutputPortId(),
                graph.nodes() == null ? null : graph.nodes().stream().map(this::toResponse).toList(),
                graph.ports() == null ? null : graph.ports().stream().map(this::toResponse).toList(),
                graph.connections() == null ? null : graph.connections().stream().map(this::toResponse).toList()
        );
    }

    private AgentRunNodeResponse toResponse(final AgentRunNode node) {
        return new AgentRunNodeResponse(
                node.sourceNodeId(),
                node.agentName(),
                new NodePositionResponse(node.position().x(), node.position().y()),
                node.scopeMode()
        );
    }

    private AgentRunPortResponse toResponse(final AgentRunPort port) {
        return new AgentRunPortResponse(
                port.sourcePortId(),
                port.sourceNodeId(),
                port.direction(),
                port.name(),
                port.order()
        );
    }

    private AgentRunConnectionResponse toResponse(final AgentRunConnection connection) {
        return new AgentRunConnectionResponse(
                connection.sourceConnectionId(),
                connection.sourceOutputPortId(),
                connection.targetInputPortId()
        );
    }

    private AgentWorkflowRunExecutionEdgeResponse toResponse(final AgentWorkflowRunExecutionEdge edge) {
        return new AgentWorkflowRunExecutionEdgeResponse(
                edge.sourceNodeRunId(),
                edge.targetNodeRunId(),
                edge.sourceType()
        );
    }

    private com.fasterxml.jackson.databind.JsonNode toJsonNode(final AgentNodeRunOutputDocument output) {
        if (output == null) {
            return null;
        }
        try {
            return this.objectMapper.readTree(output.jsonValue());
        } catch (final JsonProcessingException exception) {
            throw new IllegalStateException("Agent output document is not valid JSON.", exception);
        }
    }

    private Node toDomain(final NodeRequest request) {
        return new Node(
                request.id(),
                request.targetId(),
                request.inputMode(),
                request.inputs() == null ? null : request.inputs().stream().map(this::toDomain).toList(),
                request.outputs() == null ? null : request.outputs().stream().map(this::toDomain).toList(),
                request.position() == null ? null : new NodePosition(request.position().x(), request.position().y()),
                request.scopeMode()
        );
    }

    private NodeResponse toResponse(final Node node) {
        return new NodeResponse(
                node.id(),
                node.targetId(),
                node.inputMode(),
                node.inputs() == null ? null : node.inputs().stream().map(this::toResponse).toList(),
                node.outputs() == null ? null : node.outputs().stream().map(this::toResponse).toList(),
                node.position() == null ? null : new NodePositionResponse(node.position().x(), node.position().y()),
                node.scopeMode()
        );
    }

    private WorkflowConnection toDomain(final WorkflowConnectionRequest request) {
        if (request == null) {
            return null;
        }
        return new WorkflowConnection(request.id(), request.sourceOutputPortId(), request.targetInputPortId());
    }

    private WorkflowConnectionResponse toResponse(final WorkflowConnection connection) {
        return new WorkflowConnectionResponse(connection.id(), connection.sourceOutputPortId(), connection.targetInputPortId());
    }

    private NodePort toDomain(final NodePortRequest request) {
        if (request == null) {
            return null;
        }
        return new NodePort(request.id(), request.name(), request.description(), request.order());
    }

    private NodePortResponse toResponse(final NodePort port) {
        return new NodePortResponse(port.id(), port.name(), port.description(), port.order());
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
                    nodeRun.inputMode(),
                    new NodePositionResponse(nodeRun.position().x(), nodeRun.position().y()),
                    nodeRun.executionFrameId(),
                    nodeRun.enteredViaInputPortId(),
                    nodeRun.activationFrameId(),
                    nodeRun.selectedOutputPortId(),
                    nodeRun.status(),
                    nodeRun.output() == null ? null : this.objectMapper.readTree(nodeRun.output().jsonValue()),
                    nodeRun.failure() == null ? null : this.toResponse(nodeRun.failure()),
                    nodeRun.createdAt(),
                    nodeRun.startedAt(),
                    nodeRun.finishedAt(),
                    nodeRun.repositoryId()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalStateException("Agent workflow run JSON is not valid JSON.", exception);
        }
    }

    private AgentNodeRunFailureResponse toResponse(final AgentNodeRunFailure failure) {
        return new AgentNodeRunFailureResponse(failure.code(), failure.message());
    }

    private AgentConnectionResolutionResponse toResponse(final AgentConnectionResolution resolution) {
        try {
            return new AgentConnectionResolutionResponse(
                    resolution.id(),
                    resolution.executionFrameId(),
                    resolution.sourceNodeRunId(),
                    resolution.sourceConnectionId(),
                    resolution.targetInputPortId(),
                    resolution.resolutionType(),
                    resolution.payload() == null ? null : this.objectMapper.readTree(resolution.payload().jsonValue()),
                    resolution.consumedByNodeRunId(),
                    resolution.createdAt(),
                    resolution.targetRepositoryId()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalStateException("Agent connection resolution payload is not valid JSON.", exception);
        }
    }

}
