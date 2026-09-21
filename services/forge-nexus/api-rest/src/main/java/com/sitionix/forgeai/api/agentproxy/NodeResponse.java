package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeType;
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
        AgentNodeType nodeType,
        boolean includeTaskRepositories,
        List<UUID> workspaceRepositoryIds) {
    public NodeResponse(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortResponse> inputs,
        List<NodePortResponse> outputs,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey,
        AgentNodeType nodeType) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, contextGroupKey, nodeType, true, java.util.List.of());
    }

    public NodeResponse {
        if (nodeType == null) {
            nodeType = AgentNodeType.AGENT;
        }
    }

    public NodeResponse(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortResponse> inputs,
        List<NodePortResponse> outputs,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, contextGroupKey,
                AgentNodeType.AGENT);
    }

    public NodeResponse(UUID id,UUID targetId,String inputMode,List<NodePortResponse> inputs,List<NodePortResponse> outputs,NodePositionResponse position,String scopeMode) {
        this(id,targetId,inputMode,inputs,outputs,position,scopeMode,"FRESH_EACH_NODE_RUN");
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
