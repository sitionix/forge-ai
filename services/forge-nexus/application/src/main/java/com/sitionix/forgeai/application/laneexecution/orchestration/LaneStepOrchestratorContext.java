package com.sitionix.forgeai.application.laneexecution.orchestration;

import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.CodexLaneWorkspace;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import java.util.UUID;

public record LaneStepOrchestratorContext(
        ReadyToStartLane lane,
        AgentExecutionInput<AgentTicketPayload> input,
        LaneStrategy strategy,
        LaneStrategyStep step,
        CodexLaneWorkspace workspace,
        UUID executionId,
        String sessionId,
        int totalSteps
) {
}
