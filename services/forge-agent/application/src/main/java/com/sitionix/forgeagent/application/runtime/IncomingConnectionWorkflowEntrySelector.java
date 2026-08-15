package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class IncomingConnectionWorkflowEntrySelector implements WorkflowEntrySelector {

    @Override
    public List<RunNode> selectEntries(final WorkflowRunGraph graph) {
        final Set<java.util.UUID> targetPorts = graph.connections().stream()
                .map(com.sitionix.forgeagent.domain.model.RunConnection::targetInputPortId)
                .collect(Collectors.toSet());
        return graph.nodes().stream()
                .filter(node -> this.hasEntryInput(graph, targetPorts, node))
                .toList();
    }

    private boolean hasEntryInput(final WorkflowRunGraph graph, final Set<java.util.UUID> targetPorts, final RunNode node) {
        final List<RunPort> inputPorts = graph.ports().stream()
                .filter(port -> port.direction() == PortDirection.INPUT)
                .filter(port -> port.sourceNodeId().equals(node.sourceNodeId()))
                .toList();
        return inputPorts.isEmpty() || inputPorts.stream().anyMatch(port -> !targetPorts.contains(port.sourcePortId()));
    }
}
