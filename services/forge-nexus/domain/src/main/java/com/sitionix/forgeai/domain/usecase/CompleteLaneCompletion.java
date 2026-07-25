package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.lanecompletion.LaneCompletionCommands;

public interface CompleteLaneCompletion {

    void completeLane(LaneCompletionCommands.CompleteLane command);
}
