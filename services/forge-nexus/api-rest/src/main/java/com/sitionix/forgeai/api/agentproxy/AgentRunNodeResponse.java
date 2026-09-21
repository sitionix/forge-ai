package com.sitionix.forgeai.api.agentproxy;

import java.util.List;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeType;
import java.util.UUID;

public record AgentRunNodeResponse(
        UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey,
        AgentNodeType nodeType,
        List<UUID> resolvedWorkspaceRepositoryIds) {
    public AgentRunNodeResponse(
        UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey,
        AgentNodeType nodeType) {
        this(sourceNodeId, agentName, position, scopeMode, contextMode, contextGroupKey, nodeType, java.util.List.of());
    }

    public AgentRunNodeResponse {
        if (nodeType == null) {
            nodeType = AgentNodeType.AGENT;
        }
    }

    public AgentRunNodeResponse(
        UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey) {
        this(sourceNodeId, agentName, position, scopeMode, contextMode, contextGroupKey, AgentNodeType.AGENT);
    }

    public AgentRunNodeResponse(UUID sourceNodeId,String agentName,NodePositionResponse position,String scopeMode) { this(sourceNodeId,agentName,position,scopeMode,null); }

    public AgentRunNodeResponse(UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode,
        String contextMode) {
        this(sourceNodeId, agentName, position, scopeMode, contextMode, null);
    }
}
