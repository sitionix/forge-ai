package com.sitionix.forgeai.infrastructure.mongodb.adapter.laneexecution;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.infrastructure.mongodb.LaneExecutionEntityMapper;
import com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution.LaneExecutionJpaRepository;
import com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution.LaneStepExecutionJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneExecutionRepositoryImpl implements LaneExecutionRepository {

    private final LaneExecutionJpaRepository laneExecutionJpaRepository;
    private final LaneStepExecutionJpaRepository laneStepExecutionJpaRepository;
    private final LaneExecutionEntityMapper laneExecutionEntityMapper;

    @Override
    public LaneExecution saveExecution(final LaneExecution execution) {
        this.laneExecutionJpaRepository.save(this.laneExecutionEntityMapper.asLaneExecutionDocument(execution));
        return execution;
    }

    @Override
    public void saveStepExecution(final LaneStepExecution stepExecution) {
        this.laneStepExecutionJpaRepository.save(this.laneExecutionEntityMapper.asLaneStepExecutionDocument(stepExecution));
    }

    @Override
    public void updateCurrentStep(final LaneExecution execution) {
        this.saveExecution(execution);
    }
}
