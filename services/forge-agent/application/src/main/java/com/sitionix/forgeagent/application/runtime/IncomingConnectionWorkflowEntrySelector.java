package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.RunNode;
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
        final Set<java.util.UUID> nodesWithIncoming = graph.ports().stream()
                .filter(port -> targetPorts.contains(port.sourcePortId()))
                .map(com.sitionix.forgeagent.domain.model.RunPort::sourceNodeId)
                .collect(Collectors.toSet());
        return graph.nodes().stream()
                .filter(node -> !nodesWithIncoming.contains(node.sourceNodeId()))
                .toList();
    }
}
