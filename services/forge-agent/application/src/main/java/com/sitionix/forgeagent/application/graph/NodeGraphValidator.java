package com.sitionix.forgeagent.application.graph;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import java.util.ArrayList;
import java.util.Collection;
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
        for (final Node node : normalizedNodes) {
            if (!nodeIds.add(node.id())) {
                throw new ValidationException("DUPLICATE_NODE_ID", "Workflow node IDs must be unique.");
            }
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
        return new Node(node.id(), node.targetId(), dependencies, inputMode, position);
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
