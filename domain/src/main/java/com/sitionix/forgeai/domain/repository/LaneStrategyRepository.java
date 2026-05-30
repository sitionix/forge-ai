package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;

public interface LaneStrategyRepository {
    LaneStrategy findByAgentId(String agentId);
}
