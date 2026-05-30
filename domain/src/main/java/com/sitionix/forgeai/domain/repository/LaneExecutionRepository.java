package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;

public interface LaneExecutionRepository {
    LaneExecution saveExecution(LaneExecution execution);

    void saveStepExecution(LaneStepExecution stepExecution);

    void updateCurrentStep(LaneExecution execution);
}
