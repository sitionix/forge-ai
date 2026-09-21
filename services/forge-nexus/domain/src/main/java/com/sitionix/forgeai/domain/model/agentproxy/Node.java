package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;
import java.util.UUID;

public record Node(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePort> inputs,
        List<NodePort> outputs,
        NodePosition position,
        String scopeMode,
        String contextMode,
        String contextGroupKey,
        AgentNodeType nodeType,
        boolean includeTaskRepositories,
        List<UUID> workspaceRepositoryIds) {
    public Node(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePort> inputs,
        List<NodePort> outputs,
        NodePosition position,
        String scopeMode,
        String contextMode,
        String contextGroupKey,
        AgentNodeType nodeType) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, contextGroupKey, nodeType, true, java.util.List.of());
    }

    public Node(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePort> inputs,
        List<NodePort> outputs,
        NodePosition position,
        String scopeMode,
        String contextMode,
        String contextGroupKey) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, contextGroupKey,
                AgentNodeType.AGENT);
    }

    public Node {
        if (nodeType == null) {
            nodeType = AgentNodeType.AGENT;
        }
        if (contextMode == null) {
            contextMode = "FRESH_EACH_NODE_RUN";
        }
    }
    public Node(UUID id, UUID targetId, String inputMode, List<NodePort> inputs, List<NodePort> outputs,
                NodePosition position, String scopeMode) {
        this(id,targetId,inputMode,inputs,outputs,position,scopeMode,"FRESH_EACH_NODE_RUN");
    }

    public Node(UUID id,
        UUID targetId,
        String inputMode,
        List<NodePort> inputs,
        List<NodePort> outputs,
        NodePosition position,
        String scopeMode,
        String contextMode) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, null);
    }
}
