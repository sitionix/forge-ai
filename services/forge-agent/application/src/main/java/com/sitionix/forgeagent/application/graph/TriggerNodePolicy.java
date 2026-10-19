package com.sitionix.forgeagent.application.graph;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.*;
import java.util.List;
import java.util.UUID;

final class TriggerNodePolicy {
    private TriggerNodePolicy() { }
    static void validate(Node node) {
        if (node.nodeType() != NodeType.TRIGGER) return;
        if (node.targetId() != null || node.scopeMode() != NodeScopeMode.GLOBAL
                || node.contextMode() != NodeContextMode.FRESH_EACH_NODE_RUN || node.contextGroupKey() != null
                || node.inputs() == null || node.inputs().size() != 1
                || node.outputs() == null || node.outputs().size() != 1
                || !node.includeTaskRepositories() || !node.workspaceRepositoryIds().isEmpty()) {
            throw invalid("Trigger requires one entry, one output, GLOBAL scope and no agent or context settings.");
        }
    }
    static void validateEntry(List<Node> nodes,List<WorkflowConnection> connections,UUID entry) {
        var triggers=nodes.stream().filter(n->n.nodeType()==NodeType.TRIGGER).toList();
        if (triggers.isEmpty()) return;
        if (triggers.size()!=1) throw invalid("A workflow supports one Trigger entry.");
        UUID input=triggers.getFirst().inputs().getFirst().id();
        if (!input.equals(entry) || connections.stream().anyMatch(c->input.equals(c.targetInputPortId())))
            throw invalid("Trigger must be the workflow entry and cannot have incoming connections.");
    }
    private static ValidationException invalid(String message) { return new ValidationException("INVALID_TRIGGER_NODE",message); }
}
