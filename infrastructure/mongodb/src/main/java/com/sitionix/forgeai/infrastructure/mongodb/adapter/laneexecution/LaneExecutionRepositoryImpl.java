package com.sitionix.forgeai.infrastructure.mongodb.adapter.laneexecution;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneExecutionDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneStepExecutionDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution.LaneExecutionJpaRepository;
import com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution.LaneStepExecutionJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneExecutionRepositoryImpl implements LaneExecutionRepository {

    private final LaneExecutionJpaRepository laneExecutionJpaRepository;
    private final LaneStepExecutionJpaRepository laneStepExecutionJpaRepository;

    @Override
    public LaneExecution saveExecution(final LaneExecution execution) {
        this.laneExecutionJpaRepository.save(new LaneExecutionDocument(
                execution.getId(),
                execution.getTicketId(),
                execution.getLaneId(),
                execution.getAgentId(),
                execution.getScope(),
                execution.getStrategyId(),
                execution.getStrategyVersion(),
                execution.getSessionId(),
                execution.getCurrentStepId(),
                execution.getStartedAt(),
                execution.getUpdatedAt()
        ));
        return execution;
    }

    @Override
    public void saveStepExecution(final LaneStepExecution stepExecution) {
        this.laneStepExecutionJpaRepository.save(new LaneStepExecutionDocument(
                stepExecution.getId(),
                stepExecution.getExecutionId(),
                stepExecution.getStepId(),
                stepExecution.getStepOrder(),
                stepExecution.getStartedAt(),
                stepExecution.getCompletedAt(),
                stepExecution.isDone(),
                stepExecution.getResultJson(),
                stepExecution.getEvidenceJson()
        ));
    }

    @Override
    public void updateCurrentStep(final LaneExecution execution) {
        this.saveExecution(execution);
    }
}
