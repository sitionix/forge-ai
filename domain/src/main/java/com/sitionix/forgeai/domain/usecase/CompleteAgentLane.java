package com.sitionix.forgeai.domain.usecase;

import java.util.UUID;

@FunctionalInterface
public interface CompleteAgentLane {

     void completeAndPrepareAgents(final UUID laneId);
}
