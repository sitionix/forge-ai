package com.sitionix.forgeagent.api.dto;

import java.util.List;
import java.util.UUID;

public record NodeResponse(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortResponse> inputs,
        List<NodePortResponse> outputs,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey,
        String nodeType) {
    public NodeResponse {
        nodeType = nodeType == null ? "AGENT" : nodeType;
    }

    public NodeResponse(UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortResponse> inputs,
        List<NodePortResponse> outputs,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, contextGroupKey, "AGENT");
    }

    public NodeResponse(final UUID id, final UUID targetId, final String inputMode,
                        final List<NodePortResponse> inputs, final List<NodePortResponse> outputs,
                        final NodePositionResponse position, final String scopeMode) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, "FRESH_EACH_NODE_RUN");
    }

    public NodeResponse(UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortResponse> inputs,
        List<NodePortResponse> outputs,
        NodePositionResponse position,
        String scopeMode,
        String contextMode) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, null);
    }
}
