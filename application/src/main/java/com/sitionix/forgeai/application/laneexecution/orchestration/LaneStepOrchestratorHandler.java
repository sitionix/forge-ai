package com.sitionix.forgeai.application.laneexecution.orchestration;

import com.sitionix.forgeai.domain.model.laneexecution.LaneStepDoneResult;

public interface LaneStepOrchestratorHandler<T> {

    LaneStepDoneResult execute(LaneStepOrchestratorContext context, T input);
}
