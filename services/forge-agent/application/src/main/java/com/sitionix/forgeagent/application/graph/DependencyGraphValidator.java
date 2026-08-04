package com.sitionix.forgeagent.application.graph;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDependency;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DependencyGraphValidator {

    public void validate(final UUID projectId,
                         final List<AgentDefinition> agents,
                         final List<AgentDependency> dependencies) {
        final Set<UUID> knownAgentIds = new HashSet<>();
        for (final AgentDefinition agent : agents) {
            if (!projectId.equals(agent.projectId())) {
                throw new ConflictException("CROSS_PROJECT_DEPENDENCY", "Dependencies must belong to the same project.");
            }
            knownAgentIds.add(agent.id());
        }

        final Map<UUID, List<UUID>> graph = new HashMap<>();
        for (final UUID agentId : knownAgentIds) {
            graph.put(agentId, new ArrayList<>());
        }
        for (final AgentDependency dependency : dependencies) {
            if (dependency.agentId().equals(dependency.dependsOnAgentId())) {
                throw new ValidationException("SELF_DEPENDENCY", "An agent cannot depend on itself.");
            }
            if (!knownAgentIds.contains(dependency.agentId()) || !knownAgentIds.contains(dependency.dependsOnAgentId())) {
                throw new ValidationException("UNKNOWN_DEPENDENCY", "Dependencies must reference existing agents.");
            }
            graph.get(dependency.agentId()).add(dependency.dependsOnAgentId());
        }

        final Set<UUID> visiting = new HashSet<>();
        final Set<UUID> visited = new HashSet<>();
        for (final UUID agentId : knownAgentIds) {
            if (this.hasCycle(agentId, graph, visiting, visited, new ArrayDeque<>())) {
                throw new ConflictException("DEPENDENCY_GRAPH_CYCLE", "Dependency graph contains a cycle.");
            }
        }
    }

    private boolean hasCycle(final UUID agentId,
                             final Map<UUID, List<UUID>> graph,
                             final Set<UUID> visiting,
                             final Set<UUID> visited,
                             final ArrayDeque<UUID> path) {
        if (visited.contains(agentId)) {
            return false;
        }
        if (!visiting.add(agentId)) {
            return true;
        }
        path.push(agentId);
        for (final UUID dependencyId : graph.getOrDefault(agentId, List.of())) {
            if (this.hasCycle(dependencyId, graph, visiting, visited, path)) {
                return true;
            }
        }
        path.pop();
        visiting.remove(agentId);
        visited.add(agentId);
        return false;
    }
}
