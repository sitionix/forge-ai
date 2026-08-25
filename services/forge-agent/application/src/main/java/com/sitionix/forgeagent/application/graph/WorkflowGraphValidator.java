package com.sitionix.forgeagent.application.graph;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class WorkflowGraphValidator {

    public ValidatedGraph validateAndNormalize(final UUID projectId,
                                               final List<Node> nodes,
                                               final List<WorkflowConnection> connections,
                                               final UUID taskInputPortId,
                                               final Collection<AgentDefinition> targets) {
        return this.validateAndNormalize(projectId, nodes, connections, taskInputPortId, null, targets);
    }

    public ValidatedGraph validateAndNormalize(final UUID projectId,
                                               final List<Node> nodes,
                                               final List<WorkflowConnection> connections,
                                               final UUID taskInputPortId,
                                               final UUID taskOutputPortId,
                                               final Collection<AgentDefinition> targets) {
        final List<Node> normalizedNodes = nodes == null ? List.of() : nodes.stream()
                .map(this::normalizeNode)
                .toList();
        final List<WorkflowConnection> normalizedConnections = connections == null ? List.of() : connections.stream()
                .map(this::normalizeConnection)
                .toList();
        final Map<UUID, AgentDefinition> targetsById = targets.stream()
                .collect(Collectors.toMap(AgentDefinition::id, Function.identity()));
        final Set<UUID> nodeIds = new HashSet<>();
        final Set<UUID> portIds = new HashSet<>();
        final Map<UUID, UUID> inputOwnersByPortId = new HashMap<>();
        final Map<UUID, UUID> outputOwnersByPortId = new HashMap<>();
        final Map<UUID, UUID> allOwnersByPortId = new HashMap<>();
        for (final Node node : normalizedNodes) {
            if (!nodeIds.add(node.id())) {
                throw new ValidationException("DUPLICATE_NODE_ID", "Workflow node IDs must be unique.");
            }
            this.indexPorts(node.inputs(), node.id(), portIds, inputOwnersByPortId, allOwnersByPortId);
            this.indexPorts(node.outputs(), node.id(), portIds, outputOwnersByPortId, allOwnersByPortId);
            final AgentDefinition target = targetsById.get(node.targetId());
            if (target == null) {
                throw new ValidationException("UNKNOWN_NODE_TARGET", "Workflow nodes must target existing agents.");
            }
            if (!projectId.equals(target.projectId())) {
                throw new ConflictException("CROSS_PROJECT_NODE_TARGET", "Workflow node targets must belong to the same project.");
            }
        }
        this.validateConnections(normalizedConnections, inputOwnersByPortId, outputOwnersByPortId, allOwnersByPortId);
        this.validateTaskInputPort(taskInputPortId, normalizedNodes, inputOwnersByPortId, allOwnersByPortId);
        this.validateTaskOutputPort(taskOutputPortId, normalizedNodes, normalizedConnections, outputOwnersByPortId, allOwnersByPortId);
        this.validateReachability(normalizedNodes, normalizedConnections, taskInputPortId, inputOwnersByPortId, outputOwnersByPortId);
        return new ValidatedGraph(normalizedNodes, normalizedConnections, taskInputPortId, taskOutputPortId);
    }

    private Node normalizeNode(final Node node) {
        if (node == null || node.id() == null) {
            throw new ValidationException("DUPLICATE_NODE_ID", "Workflow node IDs must be unique.");
        }
        if (node.targetId() == null) {
            throw new ValidationException("UNKNOWN_NODE_TARGET", "Workflow nodes must target existing agents.");
        }
        final NodePosition position = node.position() == null ? new NodePosition(0.0, 0.0) : node.position();
        final NodeInputMode inputMode = node.inputMode() == null ? NodeInputMode.DEPENDENCIES_ONLY : node.inputMode();
        return new Node(
                node.id(),
                node.targetId(),
                inputMode,
                this.normalizePorts(node.inputs()),
                this.normalizePorts(node.outputs()),
                position,
                node.scopeMode()
        );
    }

    private List<NodePort> normalizePorts(final List<NodePort> ports) {
        if (ports == null) {
            return List.of();
        }
        final Set<String> names = new HashSet<>();
        final Set<Integer> orders = new HashSet<>();
        final List<NodePort> normalized = new ArrayList<>();
        for (final NodePort port : ports) {
            if (port == null || port.id() == null) {
                throw new ValidationException("INVALID_NODE_PORT", "Workflow node ports must have an ID.");
            }
            final String name = port.name() == null ? null : port.name().trim();
            if (name == null || name.isBlank()) {
                throw new ValidationException("INVALID_NODE_PORT", "Workflow node port names must not be blank.");
            }
            final String description = port.description() == null ? null : port.description().trim();
            if (description == null || description.isBlank()) {
                throw new ValidationException("INVALID_NODE_PORT", "Workflow node port descriptions must not be blank.");
            }
            if (!names.add(name)) {
                throw new ValidationException("DUPLICATE_NODE_PORT_NAME", "Workflow node port names must be unique per direction.");
            }
            if (port.order() < 0 || !orders.add(port.order())) {
                throw new ValidationException("INVALID_NODE_PORT_ORDER", "Workflow node port order must be unique and non-negative per direction.");
            }
            normalized.add(new NodePort(port.id(), name, description, port.order()));
        }
        for (int index = 0; index < normalized.size(); index += 1) {
            if (!orders.contains(index)) {
                throw new ValidationException("INVALID_NODE_PORT_ORDER", "Workflow node port order must be contiguous from zero.");
            }
        }
        return normalized.stream()
                .sorted(Comparator.comparingInt(NodePort::order))
                .toList();
    }

    private WorkflowConnection normalizeConnection(final WorkflowConnection connection) {
        if (connection == null || connection.id() == null) {
            throw new ValidationException("INVALID_WORKFLOW_CONNECTION", "Workflow connections must have an ID.");
        }
        return connection;
    }

    private void indexPorts(final List<NodePort> ports,
                            final UUID nodeId,
                            final Set<UUID> portIds,
                            final Map<UUID, UUID> ownersByPortId,
                            final Map<UUID, UUID> allOwnersByPortId) {
        for (final NodePort port : ports) {
            if (!portIds.add(port.id())) {
                throw new ValidationException("DUPLICATE_NODE_PORT_ID", "Workflow node port IDs must be unique in the workflow.");
            }
            ownersByPortId.put(port.id(), nodeId);
            allOwnersByPortId.put(port.id(), nodeId);
        }
    }

    private void validateConnections(final List<WorkflowConnection> connections,
                                     final Map<UUID, UUID> inputOwnersByPortId,
                                     final Map<UUID, UUID> outputOwnersByPortId,
                                     final Map<UUID, UUID> allOwnersByPortId) {
        final Set<UUID> connectionIds = new HashSet<>();
        final Set<PortPair> pairs = new HashSet<>();
        final Set<UUID> selfLoopTargetPortIds = new HashSet<>();
        final Set<UUID> externallyConnectedTargetPortIds = new HashSet<>();
        for (final WorkflowConnection connection : connections) {
            if (!connectionIds.add(connection.id())) {
                throw new ValidationException("DUPLICATE_WORKFLOW_CONNECTION_ID", "Workflow connection IDs must be unique.");
            }
            if (connection.sourceOutputPortId() == null || !allOwnersByPortId.containsKey(connection.sourceOutputPortId())) {
                throw new ValidationException("UNKNOWN_SOURCE_OUTPUT_PORT", "Workflow connection source output port must exist.");
            }
            if (!outputOwnersByPortId.containsKey(connection.sourceOutputPortId())) {
                throw new ValidationException("INVALID_SOURCE_OUTPUT_PORT", "Workflow connection source must be an OUTPUT port.");
            }
            if (connection.targetInputPortId() == null || !allOwnersByPortId.containsKey(connection.targetInputPortId())) {
                throw new ValidationException("UNKNOWN_TARGET_INPUT_PORT", "Workflow connection target input port must exist.");
            }
            if (!inputOwnersByPortId.containsKey(connection.targetInputPortId())) {
                throw new ValidationException("INVALID_TARGET_INPUT_PORT", "Workflow connection target must be an INPUT port.");
            }
            final UUID sourceNodeId = outputOwnersByPortId.get(connection.sourceOutputPortId());
            final UUID targetNodeId = inputOwnersByPortId.get(connection.targetInputPortId());
            if (sourceNodeId.equals(targetNodeId)) {
                selfLoopTargetPortIds.add(connection.targetInputPortId());
            } else {
                externallyConnectedTargetPortIds.add(connection.targetInputPortId());
            }
            if (!pairs.add(new PortPair(connection.sourceOutputPortId(), connection.targetInputPortId()))) {
                throw new ValidationException("DUPLICATE_WORKFLOW_CONNECTION", "Workflow connections must not duplicate the same source and target ports.");
            }
        }
        if (!externallyConnectedTargetPortIds.containsAll(selfLoopTargetPortIds)) {
            throw new ValidationException(
                    "UNGUARDED_SELF_NODE_CONNECTION",
                    "A self-loop requires at least one incoming connection from another node on the same input port."
            );
        }
    }

    private void validateTaskInputPort(final UUID taskInputPortId,
                                       final List<Node> nodes,
                                       final Map<UUID, UUID> inputOwnersByPortId,
                                       final Map<UUID, UUID> allOwnersByPortId) {
        if (nodes.isEmpty()) {
            if (taskInputPortId != null) {
                throw new ValidationException("UNKNOWN_TASK_INPUT_PORT", "Workflow task input port must exist.");
            }
            return;
        }
        if (taskInputPortId == null) {
            throw new ValidationException("WORKFLOW_TASK_INPUT_REQUIRED", "Workflow task input port is required.");
        }
        if (!allOwnersByPortId.containsKey(taskInputPortId)) {
            throw new ValidationException("UNKNOWN_TASK_INPUT_PORT", "Workflow task input port must exist.");
        }
        if (!inputOwnersByPortId.containsKey(taskInputPortId)) {
            throw new ValidationException("INVALID_TASK_INPUT_PORT", "Workflow task input port must be an INPUT port.");
        }
    }

    private void validateTaskOutputPort(final UUID taskOutputPortId,
                                        final List<Node> nodes,
                                        final List<WorkflowConnection> connections,
                                        final Map<UUID, UUID> outputOwnersByPortId,
                                        final Map<UUID, UUID> allOwnersByPortId) {
        if (nodes.isEmpty()) {
            if (taskOutputPortId != null) {
                throw new ValidationException("UNKNOWN_TASK_OUTPUT_PORT", "Workflow task output port must exist.");
            }
            return;
        }
        if (taskOutputPortId == null) {
            throw new ValidationException("WORKFLOW_TASK_OUTPUT_REQUIRED", "Workflow task output port is required.");
        }
        if (!allOwnersByPortId.containsKey(taskOutputPortId)) {
            throw new ValidationException("UNKNOWN_TASK_OUTPUT_PORT", "Workflow task output port must exist.");
        }
        if (!outputOwnersByPortId.containsKey(taskOutputPortId)) {
            throw new ValidationException("INVALID_TASK_OUTPUT_PORT", "Workflow task output port must be an OUTPUT port.");
        }
        if (connections.stream().anyMatch(connection -> taskOutputPortId.equals(connection.sourceOutputPortId()))) {
            throw new ValidationException("TASK_OUTPUT_PORT_NOT_TERMINAL", "Workflow Task Output must not have downstream workflow connections.");
        }
    }

    private void validateReachability(final List<Node> nodes,
                                      final List<WorkflowConnection> connections,
                                      final UUID taskInputPortId,
                                      final Map<UUID, UUID> inputOwnersByPortId,
                                      final Map<UUID, UUID> outputOwnersByPortId) {
        if (nodes.isEmpty()) {
            return;
        }
        final Map<UUID, List<UUID>> targetNodeIdsBySourceNodeId = new HashMap<>();
        for (final WorkflowConnection connection : connections) {
            final UUID sourceNodeId = outputOwnersByPortId.get(connection.sourceOutputPortId());
            final UUID targetNodeId = inputOwnersByPortId.get(connection.targetInputPortId());
            targetNodeIdsBySourceNodeId.computeIfAbsent(sourceNodeId, ignored -> new ArrayList<>()).add(targetNodeId);
        }

        final UUID startNodeId = inputOwnersByPortId.get(taskInputPortId);
        final Set<UUID> reachableNodeIds = new HashSet<>();
        final ArrayList<UUID> pendingNodeIds = new ArrayList<>();
        pendingNodeIds.add(startNodeId);
        while (!pendingNodeIds.isEmpty()) {
            final UUID nodeId = pendingNodeIds.removeLast();
            if (!reachableNodeIds.add(nodeId)) {
                continue;
            }
            pendingNodeIds.addAll(targetNodeIdsBySourceNodeId.getOrDefault(nodeId, List.of()));
        }

        for (final Node node : nodes) {
            if (!reachableNodeIds.contains(node.id())) {
                throw new ValidationException(
                        "INCONSISTENT_WORKFLOW_GRAPH",
                        "Workflow contains nodes that are not reachable from Task Input. Connect all workflow nodes to the execution flow or remove them."
                );
            }
        }
    }

    private record PortPair(UUID sourceOutputPortId, UUID targetInputPortId) {
    }

    public record ValidatedGraph(List<Node> nodes, List<WorkflowConnection> connections, UUID taskInputPortId, UUID taskOutputPortId) {
    }
}
