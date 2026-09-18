package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentNodeRun(
        UUID id,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchemaDocument agentOutputSchema,
        String inputMode,
        NodePosition position,
        UUID executionFrameId,
        UUID enteredViaInputPortId,
        UUID activationFrameId,
        UUID selectedOutputPortId,
        AgentNodeRunStatus status,
        AgentNodeRunOutputDocument output,
        AgentNodeRunFailure failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        String contextMode,
        Integer contextTrackingVersion,
        UUID retryOfNodeRunId,
        AgentNodeRunRetryEligibility retryEligibility,
        String contextGroupKey, UUID contextIterationId,
        AgentNodeType nodeType) {
    public AgentNodeRun {
        if (nodeType == null) {
            nodeType = AgentNodeType.AGENT;
        }
    }

    public AgentNodeRun(
        UUID id,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchemaDocument agentOutputSchema,
        String inputMode,
        NodePosition position,
        UUID executionFrameId,
        UUID enteredViaInputPortId,
        UUID activationFrameId,
        UUID selectedOutputPortId,
        AgentNodeRunStatus status,
        AgentNodeRunOutputDocument output,
        AgentNodeRunFailure failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        String contextMode,
        Integer contextTrackingVersion,
        UUID retryOfNodeRunId,
        AgentNodeRunRetryEligibility retryEligibility,
        String contextGroupKey, UUID contextIterationId) {
        this(id, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema, inputMode, position,
                executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId, status, output,
                failure, createdAt, startedAt, finishedAt, repositoryId, contextMode, contextTrackingVersion,
                retryOfNodeRunId, retryEligibility, contextGroupKey, contextIterationId, AgentNodeType.AGENT);
    }

    public AgentNodeRun(UUID id,UUID sourceNodeId,UUID sourceAgentId,String agentName,String agentInstructions,
                        AgentOutputSchemaDocument agentOutputSchema,String inputMode,NodePosition position,
                        UUID executionFrameId,UUID enteredViaInputPortId,UUID activationFrameId,UUID selectedOutputPortId,
                        AgentNodeRunStatus status,AgentNodeRunOutputDocument output,AgentNodeRunFailure failure,
                        Instant createdAt,Instant startedAt,Instant finishedAt,UUID repositoryId) {
        this(id,sourceNodeId,sourceAgentId,agentName,agentInstructions,agentOutputSchema,inputMode,position,
                executionFrameId,enteredViaInputPortId,activationFrameId,selectedOutputPortId,status,output,failure,
                createdAt,startedAt,finishedAt,repositoryId,"FRESH_EACH_NODE_RUN",null,null,null);
    }

    public AgentNodeRun(UUID id, UUID sourceNodeId, UUID sourceAgentId, String agentName,
                        String agentInstructions, AgentOutputSchemaDocument agentOutputSchema,
                        String inputMode, NodePosition position, UUID executionFrameId,
                        UUID enteredViaInputPortId, UUID activationFrameId, UUID selectedOutputPortId,
                        AgentNodeRunStatus status, AgentNodeRunOutputDocument output,
                        AgentNodeRunFailure failure, Instant createdAt, Instant startedAt,
                        Instant finishedAt, UUID repositoryId, String contextMode,
                        Integer contextTrackingVersion) {
        this(id, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema,
                inputMode, position, executionFrameId, enteredViaInputPortId, activationFrameId,
                selectedOutputPortId, status, output, failure, createdAt, startedAt, finishedAt,
                repositoryId, contextMode, contextTrackingVersion, null, null);
    }

    public AgentNodeRun(UUID id,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchemaDocument agentOutputSchema,
        String inputMode,
        NodePosition position,
        UUID executionFrameId,
        UUID enteredViaInputPortId,
        UUID activationFrameId,
        UUID selectedOutputPortId,
        AgentNodeRunStatus status,
        AgentNodeRunOutputDocument output,
        AgentNodeRunFailure failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId,
        String contextMode,
        Integer contextTrackingVersion,
        UUID retryOfNodeRunId,
        AgentNodeRunRetryEligibility retryEligibility) {
        this(id, sourceNodeId, sourceAgentId, agentName, agentInstructions, agentOutputSchema, inputMode, position,
                executionFrameId, enteredViaInputPortId, activationFrameId, selectedOutputPortId, status, output,
                failure, createdAt, startedAt, finishedAt, repositoryId, contextMode, contextTrackingVersion,
                retryOfNodeRunId, retryEligibility, null, null);
    }
}
