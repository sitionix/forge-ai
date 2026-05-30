package com.sitionix.forgeai.domain.model.laneexecution;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class LaneExecution {
    UUID id;
    UUID ticketId;
    UUID laneId;
    String agentId;
    String scope;
    String strategyId;
    int strategyVersion;
    String sessionId;
    String currentStepId;
    LocalDateTime startedAt;
    LocalDateTime updatedAt;
}
