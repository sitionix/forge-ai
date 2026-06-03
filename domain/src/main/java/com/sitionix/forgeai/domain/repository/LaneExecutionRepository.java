package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LaneExecutionRepository {
    LaneExecution saveExecution(LaneExecution execution);

    void saveStepExecution(LaneStepExecution stepExecution);

    void updateCurrentStep(LaneExecution execution);

    Optional<LaneExecution> findExecution(UUID executionId);

    List<LaneExecution> findActiveExecutions();

    List<LaneExecution> findActiveExecutionsByTicketId(UUID ticketId);
}
