package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunStatus;
import java.time.Instant;
import java.util.UUID;

public record NodeRunResponse(
        UUID id,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        JsonNode agentOutputSchema,
        String inputMode,
        NodePositionResponse position,
        UUID executionFrameId,
        UUID enteredViaInputPortId,
        UUID activationFrameId,
        UUID selectedOutputPortId,
        AgentNodeRunStatus status,
        JsonNode output,
        NodeRunFailureResponse failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        String contextMode,
        Integer contextTrackingVersion
) {
    public NodeRunResponse(UUID id,UUID sourceNodeId,UUID sourceAgentId,String agentName,String agentInstructions,JsonNode agentOutputSchema,String inputMode,NodePositionResponse position,UUID executionFrameId,UUID enteredViaInputPortId,UUID activationFrameId,UUID selectedOutputPortId,AgentNodeRunStatus status,JsonNode output,NodeRunFailureResponse failure,Instant createdAt,Instant startedAt,Instant finishedAt,UUID repositoryId) {
        this(id,sourceNodeId,sourceAgentId,agentName,agentInstructions,agentOutputSchema,inputMode,position,executionFrameId,enteredViaInputPortId,activationFrameId,selectedOutputPortId,status,output,failure,createdAt,startedAt,finishedAt,repositoryId,"FRESH_EACH_NODE_RUN",null);
    }
}
