package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataNodeRunRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PostgresNodeRunRepository implements NodeRunRepository {

    private static final List<String> ACTIVE_STATUSES = List.of(
            NodeRunStatus.PENDING.name(),
            NodeRunStatus.RUNNING.name()
    );

    private final SpringDataNodeRunRepository repository;
    private final AgentExecutionSessionRepository sessionRepository;

    @Override
    public List<UUID> findPendingIds() {
        return this.repository.findPendingIds();
    }

    @Override
    public List<UUID> findSuccessfulUnroutedIds() {
        return this.repository.findSuccessfulUnroutedIds();
    }

    @Override
    public Optional<UUID> findWorkflowRunIdById(final UUID nodeRunId) {
        return this.repository.findWorkflowRunIdById(nodeRunId);
    }

    @Override
    public Optional<NodeRun> findById(final UUID nodeRunId) {
        return this.repository.findById(nodeRunId).map(PostgresNodeRunMapper::toDomain);
    }

    @Override
    public Optional<NodeRun> findByIdForUpdate(final UUID nodeRunId) {
        return this.repository.findByIdForUpdate(nodeRunId).map(PostgresNodeRunMapper::toDomain);
    }

    @Override
    public List<NodeRun> findByIds(final Collection<UUID> nodeRunIds) {
        if (nodeRunIds == null || nodeRunIds.isEmpty()) {
            return List.of();
        }
        return this.repository.findAllById(nodeRunIds).stream()
                .map(PostgresNodeRunMapper::toDomain)
                .toList();
    }

    @Override
    public List<NodeRun> findByWorkflowRunId(final UUID workflowRunId) {
        return this.repository.findByWorkflowRunIdOrderByCreatedAtAscIdAsc(workflowRunId).stream()
                .map(PostgresNodeRunMapper::toDomain)
                .toList();
    }

    @Override
    public List<NodeRun> findByWorkflowRunIdAndExecutionFrameId(final UUID workflowRunId, final UUID executionFrameId) {
        return this.repository.findByWorkflowRunIdAndExecutionFrameIdOrderByCreatedAtAscIdAsc(workflowRunId, executionFrameId).stream()
                .map(PostgresNodeRunMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public NodeRun save(final NodeRun nodeRun) {
        final boolean allocate = nodeRun.contextTrackingVersion() != null
                && this.sessionRepository.findByNodeRunId(nodeRun.id()).isEmpty();
        // Agent turn allocation is performed through JDBC and has a foreign key
        // to this row, so the NodeRun must be visible before allocating it.
        final NodeRun saved = PostgresNodeRunMapper.toDomain(this.repository.saveAndFlush(PostgresNodeRunMapper.toEntity(nodeRun)));
        if (allocate) this.sessionRepository.allocate(saved, saved.executionModel().providerId());
        return saved;
    }

    @Override
    @Transactional
    public NodeRun saveAndFlush(final NodeRun nodeRun) {
        final boolean allocate = nodeRun.contextTrackingVersion() != null
                && this.sessionRepository.findByNodeRunId(nodeRun.id()).isEmpty();
        final NodeRun saved = PostgresNodeRunMapper.toDomain(this.repository.saveAndFlush(PostgresNodeRunMapper.toEntity(nodeRun)));
        if (allocate) this.sessionRepository.allocate(saved, saved.executionModel().providerId());
        return saved;
    }

    @Override
    public boolean existsActiveBySourceAgentId(final UUID agentId) {
        return this.repository.existsBySourceAgentIdAndStatusIn(agentId, ACTIVE_STATUSES);
    }
}
