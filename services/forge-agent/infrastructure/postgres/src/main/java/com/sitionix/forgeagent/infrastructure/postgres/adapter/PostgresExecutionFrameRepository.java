package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ExecutionFrameEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataExecutionFrameRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresExecutionFrameRepository implements ExecutionFrameRepository {

    private final SpringDataExecutionFrameRepository repository;

    @Override
    public ExecutionFrame save(final ExecutionFrame frame) {
        return this.toDomain(this.repository.save(this.toEntity(frame)));
    }

    @Override
    public Optional<ExecutionFrame> findById(final UUID id) {
        return this.repository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<ExecutionFrame> findByIdForUpdate(final UUID id) {
        return this.repository.findByIdForUpdate(id).map(this::toDomain);
    }

    @Override
    public List<ExecutionFrame> findByWorkflowRunId(final UUID workflowRunId) {
        return this.repository.findByWorkflowRunIdOrderByCreatedAtAscIdAsc(workflowRunId).stream()
                .map(this::toDomain)
                .toList();
    }

    private ExecutionFrame toDomain(final ExecutionFrameEntity entity) {
        return new ExecutionFrame(entity.getId(), entity.getWorkflowRunId(), entity.getParentFrameId(), entity.getCreatedAt());
    }

    private ExecutionFrameEntity toEntity(final ExecutionFrame frame) {
        final ExecutionFrameEntity entity = new ExecutionFrameEntity();
        entity.setId(frame.id());
        entity.setWorkflowRunId(frame.workflowRunId());
        entity.setParentFrameId(frame.parentFrameId());
        entity.setCreatedAt(frame.createdAt());
        return entity;
    }
}
