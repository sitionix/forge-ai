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
        return new ValidatedGraph(normalizedNodes, normalizedConnections);
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
                position
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
                throw new ValidationException("SELF_NODE_CONNECTION", "A workflow node cannot connect to itself.");
            }
            if (!pairs.add(new PortPair(connection.sourceOutputPortId(), connection.targetInputPortId()))) {
                throw new ValidationException("DUPLICATE_WORKFLOW_CONNECTION", "Workflow connections must not duplicate the same source and target ports.");
            }
        }
    }

    private record PortPair(UUID sourceOutputPortId, UUID targetInputPortId) {
    }

    public record ValidatedGraph(List<Node> nodes, List<WorkflowConnection> connections) {
    }
}
