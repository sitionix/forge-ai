package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataNodeRunRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresNodeRunRepository implements NodeRunRepository {

    private final SpringDataNodeRunRepository repository;

    @Override
    public List<UUID> findPendingIds() {
        return this.repository.findPendingIds();
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
    public NodeRun save(final NodeRun nodeRun) {
        return PostgresNodeRunMapper.toDomain(this.repository.save(PostgresNodeRunMapper.toEntity(nodeRun)));
    }
}
