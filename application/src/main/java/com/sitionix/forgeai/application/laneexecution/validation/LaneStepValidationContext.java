package com.sitionix.forgeai.application.laneexecution.validation;

import com.sitionix.forgeai.domain.model.codex.CodexLaneWorkspace;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import java.util.UUID;

public record LaneStepValidationContext(
        ReadyToStartLane lane,
        LaneStrategy strategy,
        LaneStrategyStep step,
        CodexLaneWorkspace workspace,
        UUID executionId,
        String sessionId
) {
}
