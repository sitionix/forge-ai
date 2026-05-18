package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.CompleteArchitectLaneCommand;

@FunctionalInterface
public interface CompleteArchitectLane {
    void complete(CompleteArchitectLaneCommand command);
}
