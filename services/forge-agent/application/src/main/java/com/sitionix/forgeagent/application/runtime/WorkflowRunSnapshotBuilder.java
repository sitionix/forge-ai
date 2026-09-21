package com.sitionix.forgeagent.application.runtime;

import java.util.LinkedHashSet;
import com.sitionix.forgeagent.application.graph.WorkflowWorkspaceValidator;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentExecutionProviderCapability;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeType;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.AgentExecutionProviderCapabilities;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkflowRunSnapshotBuilder {

    private final WorkflowWorkspaceValidator workspaceValidator;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final AgentExecutionProviderCapabilities providerCapabilities;

    public WorkflowRunGraph build(final UUID workflowRunId, final Workflow workflow, final List<UUID> taskRepositorySnapshot) {
        this.workspaceValidator.validate(workflow.projectId(), workflow.nodes());
        com.sitionix.forgeagent.domain.model.ContextIterationPolicy.validateScopes(workflow.nodes());
        final Map<UUID, AgentDefinition> agentsById = this.agentDefinitionRepository.findByIds(this.agentIds(workflow.nodes())).stream()
                .collect(Collectors.toMap(AgentDefinition::id, Function.identity()));
        return new WorkflowRunGraph(
                workflowRunId,
                workflow.taskInputPortId(),
                workflow.taskOutputPortId(),
                workflow.nodes().stream().map(node -> this.runNode(workflowRunId, node, agentsById, taskRepositorySnapshot)).toList(),
                workflow.nodes().stream()
                        .flatMap(node -> java.util.stream.Stream.concat(
                                this.runPorts(workflowRunId, node, PortDirection.INPUT, node.inputs()).stream(),
                                this.runPorts(workflowRunId, node, PortDirection.OUTPUT, node.outputs()).stream()
                        ))
                        .toList(),
                workflow.connections().stream().map(connection -> this.runConnection(workflowRunId, connection)).toList()
        );
    }

    private Collection<UUID> agentIds(final List<Node> nodes) {
        return nodes.stream().filter(node -> node.nodeType() == NodeType.AGENT).map(Node::targetId).collect(Collectors.toSet());
    }

    private RunNode runNode(final UUID workflowRunId, final Node node, final Map<UUID, AgentDefinition> agentsById, final List<UUID> taskRepositorySnapshot) {
        if (node.nodeType() == NodeType.MANUAL) {
            if (node.targetId() != null) {
                throw new ValidationException("INVALID_MANUAL_NODE_TARGET", "Manual workflow nodes must not target an agent.");
            }
            return new RunNode(workflowRunId, node.id(), null, null, null, null, null,
                    node.inputMode(), node.position(), node.scopeMode(), node.contextMode(), node.contextGroupKey(), node.nodeType());
        }
        final AgentDefinition agent = agentsById.get(node.targetId());
        if (agent == null) {
            throw new ConflictException("SOURCE_AGENT_NOT_FOUND", "Source agent was not found.");
        }
        final NodeRunExecutionModel executionModel = this.executionModel(agent.model());
        if (node.contextMode().reusable()
                && !this.providerCapabilities.supports(
                        executionModel.providerId(), AgentExecutionProviderCapability.DURABLE_CONTEXT)) {
            throw new ValidationException(
                    "AGENT_CONTEXT_MODE_UNSUPPORTED",
                    agent.name() + " cannot keep context during an execution because its provider does not support durable context."
            );
        }
        return new RunNode(
                workflowRunId,
                node.id(),
                agent.id(),
                agent.name(),
                agent.instructions(),
                agent.outputSchema(),
                executionModel,
                node.inputMode(),
                node.position(),
                node.scopeMode(),
                node.contextMode(),
                node.contextGroupKey(),
                node.nodeType(),
                this.resolveWorkspaceRepositories(node, taskRepositorySnapshot)
        );
    }

    private List<UUID> resolveWorkspaceRepositories(final Node node, final List<UUID> taskRepositorySnapshot) {
        if (node.scopeMode() != com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL) return List.of();
        final var resolved = new LinkedHashSet<UUID>();
        if (node.includeTaskRepositories()) resolved.addAll(taskRepositorySnapshot);
        resolved.addAll(node.workspaceRepositoryIds());
        return List.copyOf(resolved);
    }

    private NodeRunExecutionModel executionModel(final AgentModelSelection selection) {
        if (selection == null || this.isBlank(selection.providerId()) || this.isBlank(selection.modelId())) {
            throw new ConflictException("AGENT_MODEL_NOT_CONFIGURED", "Source agent model is not configured.");
        }
        return new NodeRunExecutionModel(selection.providerId(), selection.modelId(), this.isBlank(selection.effortId()) ? null : selection.effortId());
    }

    private List<RunPort> runPorts(final UUID workflowRunId, final Node node, final PortDirection direction, final List<NodePort> ports) {
        return ports.stream()
                .map(port -> new RunPort(workflowRunId, port.id(), node.id(), direction, port.name(), port.description(), port.order()))
                .toList();
    }

    private RunConnection runConnection(final UUID workflowRunId, final WorkflowConnection connection) {
        return new RunConnection(workflowRunId, connection.id(), connection.sourceOutputPortId(), connection.targetInputPortId());
    }

    private boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
