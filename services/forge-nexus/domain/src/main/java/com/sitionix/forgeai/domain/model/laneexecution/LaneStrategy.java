package com.sitionix.forgeai.domain.model.laneexecution;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LaneStrategy {
    String agentId;
    int version;
    String sessionMode;
    List<LaneStrategyStep> steps;
}
