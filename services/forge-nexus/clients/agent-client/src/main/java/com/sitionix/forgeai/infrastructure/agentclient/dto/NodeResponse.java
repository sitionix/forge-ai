package com.sitionix.forgeai.infrastructure.agentclient.dto;

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
        String contextMode
) {
    public NodeResponse(UUID id,UUID targetId,String inputMode,List<NodePortResponse> inputs,List<NodePortResponse> outputs,NodePositionResponse position,String scopeMode) { this(id,targetId,inputMode,inputs,outputs,position,scopeMode,"FRESH_EACH_NODE_RUN"); }
}
