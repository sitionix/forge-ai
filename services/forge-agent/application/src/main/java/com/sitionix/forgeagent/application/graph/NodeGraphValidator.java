package com.sitionix.forgeagent.application.graph;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodePosition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NodeGraphValidator {

    public List<Node> validateAndNormalize(final UUID projectId,
                                           final List<Node> nodes,
                                           final Collection<AgentDefinition> targets) {
        final List<Node> normalizedNodes = nodes == null ? List.of() : nodes.stream()
                .map(this::normalizeNode)
                .toList();
        final Map<UUID, AgentDefinition> targetsById = targets.stream()
                .collect(Collectors.toMap(AgentDefinition::id, Function.identity()));
        final Set<UUID> nodeIds = new HashSet<>();
        final Set<UUID> portIds = new HashSet<>();
        for (final Node node : normalizedNodes) {
            if (!nodeIds.add(node.id())) {
                throw new ValidationException("DUPLICATE_NODE_ID", "Workflow node IDs must be unique.");
            }
            this.rejectDuplicatePortIds(node.inputs(), portIds);
            this.rejectDuplicatePortIds(node.outputs(), portIds);
            final AgentDefinition target = targetsById.get(node.targetId());
            if (target == null) {
                throw new ValidationException("UNKNOWN_NODE_TARGET", "Workflow nodes must target existing agents.");
            }
            if (!projectId.equals(target.projectId())) {
                throw new ConflictException("CROSS_PROJECT_NODE_TARGET", "Workflow node targets must belong to the same project.");
            }
        }
        for (final Node node : normalizedNodes) {
            for (final UUID dependencyId : node.dependsOnNodeIds()) {
                if (node.id().equals(dependencyId)) {
                    throw new ValidationException("SELF_NODE_DEPENDENCY", "A workflow node cannot depend on itself.");
                }
                if (!nodeIds.contains(dependencyId)) {
                    throw new ValidationException("UNKNOWN_NODE_DEPENDENCY", "Workflow node dependencies must reference nodes in the same workflow.");
                }
            }
        }
        this.rejectCycles(normalizedNodes);
        return normalizedNodes;
    }

    private Node normalizeNode(final Node node) {
        if (node == null || node.id() == null) {
            throw new ValidationException("DUPLICATE_NODE_ID", "Workflow node IDs must be unique.");
        }
        if (node.targetId() == null) {
            throw new ValidationException("UNKNOWN_NODE_TARGET", "Workflow nodes must target existing agents.");
        }
        final NodePosition position = node.position() == null ? new NodePosition(0.0, 0.0) : node.position();
        final List<UUID> dependencies = node.dependsOnNodeIds() == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(node.dependsOnNodeIds()));
        final NodeInputMode inputMode = node.inputMode() == null ? NodeInputMode.DEPENDENCIES_ONLY : node.inputMode();
        return new Node(
                node.id(),
                node.targetId(),
                dependencies,
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

    private void rejectDuplicatePortIds(final List<NodePort> ports, final Set<UUID> portIds) {
        for (final NodePort port : ports) {
            if (!portIds.add(port.id())) {
                throw new ValidationException("DUPLICATE_NODE_PORT_ID", "Workflow node port IDs must be unique in the workflow.");
            }
        }
    }

    private void rejectCycles(final List<Node> nodes) {
        final Map<UUID, List<UUID>> dependenciesByNode = new HashMap<>();
        nodes.forEach(node -> dependenciesByNode.put(node.id(), node.dependsOnNodeIds()));
        final Set<UUID> visited = new HashSet<>();
        final Set<UUID> visiting = new HashSet<>();
        for (final Node node : nodes) {
            if (this.hasCycle(node.id(), dependenciesByNode, visited, visiting)) {
                throw new ConflictException("WORKFLOW_GRAPH_CYCLE", "Workflow graph contains a cycle.");
            }
        }
    }

    private boolean hasCycle(final UUID nodeId,
                             final Map<UUID, List<UUID>> dependenciesByNode,
                             final Set<UUID> visited,
                             final Set<UUID> visiting) {
        if (visited.contains(nodeId)) {
            return false;
        }
        if (!visiting.add(nodeId)) {
            return true;
        }
        for (final UUID dependencyId : dependenciesByNode.getOrDefault(nodeId, List.of())) {
            if (this.hasCycle(dependencyId, dependenciesByNode, visited, visiting)) {
                return true;
            }
        }
        visiting.remove(nodeId);
        visited.add(nodeId);
        return false;
    }
}
