package com.sitionix.forgeai.api.agentproxy;

import java.util.List;
import java.util.UUID;

public record NodeRequest(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortRequest> inputs,
        List<NodePortRequest> outputs,
        NodePositionRequest position,
        String scopeMode,
        String contextMode
) {
    public NodeRequest(UUID id,UUID targetId,String inputMode,List<NodePortRequest> inputs,List<NodePortRequest> outputs,NodePositionRequest position,String scopeMode) {
        this(id,targetId,inputMode,inputs,outputs,position,scopeMode,null);
    }
}
