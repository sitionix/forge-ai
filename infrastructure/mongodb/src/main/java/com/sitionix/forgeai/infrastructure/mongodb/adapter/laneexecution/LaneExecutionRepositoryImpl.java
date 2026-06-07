package com.sitionix.forgeai.infrastructure.mongodb.adapter.laneexecution;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.infrastructure.mongodb.LaneExecutionEntityMapper;
import com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution.LaneExecutionJpaRepository;
import com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution.LaneStepExecutionJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    @Override
    public Optional<LaneExecution> findExecution(final UUID executionId) {
        return this.laneExecutionJpaRepository.findById(executionId)
                .map(this.laneExecutionEntityMapper::asLaneExecution);
    }

    @Override
    public List<LaneStepExecution> findStepExecutions(final UUID executionId) {
        return this.laneStepExecutionJpaRepository.findByExecutionIdOrderByStepOrderAsc(executionId).stream()
                .map(this.laneExecutionEntityMapper::asLaneStepExecution)
                .toList();
    }

    @Override
    public List<LaneExecution> findByTicketId(final UUID ticketId) {
        return this.laneExecutionJpaRepository.findByTicketId(ticketId).stream()
                .map(this.laneExecutionEntityMapper::asLaneExecution)
                .toList();
    }

    @Override
    public List<LaneExecution> findActiveExecutions() {
        return this.laneExecutionJpaRepository.findByStatusNotIn(List.of(
                        LaneExecutionStatus.COMPLETED,
                        LaneExecutionStatus.FAILED,
                        LaneExecutionStatus.INTERRUPTED,
                        LaneExecutionStatus.CANCELLED
                )).stream()
                .map(this.laneExecutionEntityMapper::asLaneExecution)
                .toList();
    }

    @Override
    public List<LaneExecution> findActiveExecutionsByTicketId(final UUID ticketId) {
        return this.laneExecutionJpaRepository.findByTicketIdAndStatusNotIn(ticketId, List.of(
                        LaneExecutionStatus.COMPLETED,
                        LaneExecutionStatus.FAILED,
                        LaneExecutionStatus.INTERRUPTED,
                        LaneExecutionStatus.CANCELLED
                )).stream()
                .map(this.laneExecutionEntityMapper::asLaneExecution)
                .toList();
    }

    @Override
    public void deleteByTicketId(final UUID ticketId) {
        final List<UUID> executionIds = this.laneExecutionJpaRepository.findByTicketId(ticketId).stream()
                .map(document -> document.getId())
                .toList();
        if (!executionIds.isEmpty()) {
            this.laneStepExecutionJpaRepository.deleteByExecutionIdIn(executionIds);
        }
        this.laneExecutionJpaRepository.deleteByTicketId(ticketId);
    }
}
