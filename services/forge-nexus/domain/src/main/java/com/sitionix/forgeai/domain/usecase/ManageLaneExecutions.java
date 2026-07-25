package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import java.util.List;
import java.util.UUID;

public interface ManageLaneExecutions {

    List<LaneExecution> findActiveExecutions();

    LaneExecution getExecution(UUID executionId);

    LaneExecution interrupt(UUID executionId);
}
