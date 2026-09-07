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
        String contextMode
) {
    public Node { if (contextMode == null) contextMode="FRESH_EACH_NODE_RUN"; }
    public Node(UUID id, UUID targetId, String inputMode, List<NodePort> inputs, List<NodePort> outputs,
                NodePosition position, String scopeMode) {
        this(id,targetId,inputMode,inputs,outputs,position,scopeMode,"FRESH_EACH_NODE_RUN");
    }
}
