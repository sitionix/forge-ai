package com.sitionix.forgeai.domain.model.laneexecution;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LaneStepExecution {
    UUID id;
    UUID executionId;
    String stepId;
    int stepOrder;
    LocalDateTime startedAt;
    LocalDateTime completedAt;
    boolean done;
    String resultJson;
    String evidenceJson;
}
