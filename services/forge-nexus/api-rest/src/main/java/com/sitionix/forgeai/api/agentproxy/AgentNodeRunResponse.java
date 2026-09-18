package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeType;
import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunStatus;
import java.time.Instant;
import java.util.UUID;

public record AgentNodeRunResponse(
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
        AgentNodeRunFailureResponse failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        String contextMode,
        Integer contextTrackingVersion,
        UUID retryOfNodeRunId,
        AgentNodeRunRetryEligibilityResponse retryEligibility,
        String contextGroupKey, UUID contextIterationId,
        AgentNodeType nodeType) {
    public AgentNodeRunResponse {
        if (nodeType == null) {
            nodeType = AgentNodeType.AGENT;
        }
    }

    public AgentNodeRunResponse(
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
        AgentNodeRunFailureResponse failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        String contextMode,
        Integer contextTrackingVersion,
        UUID retryOfNodeRunId,
        AgentNodeRunRetryEligibilityResponse retryEligibility,
        String contextGroupKey, UUID contextIterationId) {
        this(id, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema, inputMode, position,
                executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId, status, output,
                failure, createdAt, startedAt, finishedAt, repositoryId, contextMode, contextTrackingVersion,
                retryOfNodeRunId, retryEligibility, contextGroupKey, contextIterationId, AgentNodeType.AGENT);
    }

    public AgentNodeRunResponse(UUID id,UUID sourceNodeId,UUID sourceAgentId,String agentName,String agentInstructions,JsonNode agentOutputSchema,String inputMode,NodePositionResponse position,UUID executionFrameId,UUID enteredViaInputPortId,UUID activationFrameId,UUID selectedOutputPortId,AgentNodeRunStatus status,JsonNode output,AgentNodeRunFailureResponse failure,Instant createdAt,Instant startedAt,Instant finishedAt,UUID repositoryId) {
this(id,sourceNodeId,sourceAgentId,agentName,agentInstructions,agentOutputSchema,inputMode,position,executionFrameId,enteredViaInputPortId,activationFrameId,selectedOutputPortId,status,output,failure,createdAt,startedAt,finishedAt,repositoryId,"FRESH_EACH_NODE_RUN",null,null,null);
    }

    public AgentNodeRunResponse(UUID id,
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
        AgentNodeRunFailureResponse failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        String contextMode,
        Integer contextTrackingVersion,
        UUID retryOfNodeRunId,
        AgentNodeRunRetryEligibilityResponse retryEligibility) {
        this(id, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema, inputMode, position,
                executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId, status, output,
                failure, createdAt, startedAt, finishedAt, repositoryId, contextMode, contextTrackingVersion,
                retryOfNodeRunId, retryEligibility, null, null);
    }
}
