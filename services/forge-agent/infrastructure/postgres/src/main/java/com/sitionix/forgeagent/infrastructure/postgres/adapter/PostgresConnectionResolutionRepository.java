package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.ConnectionResolutionType;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ConnectionResolutionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataConnectionResolutionRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresConnectionResolutionRepository implements ConnectionResolutionRepository {

    private final SpringDataConnectionResolutionRepository repository;

    @Override
    public List<ConnectionResolution> findByWorkflowRunAndFrame(final UUID workflowRunId, final UUID executionFrameId) {
        return this.repository.findByWorkflowRunIdAndExecutionFrameIdOrderByCreatedAtAscIdAsc(workflowRunId, executionFrameId).stream()
                .map(PostgresConnectionResolutionRepository::toDomain)
                .toList();
    }

    public static ConnectionResolution toDomain(final ConnectionResolutionEntity entity) {
        return new ConnectionResolution(
                entity.getId(),
                entity.getWorkflowRunId(),
                entity.getExecutionFrameId(),
                entity.getSourceNodeRunId(),
                entity.getSourceConnectionId(),
                entity.getTargetInputPortId(),
                ConnectionResolutionType.valueOf(entity.getResolutionType()),
                entity.getPayload() == null ? null : new NodeRunOutput(entity.getPayload()),
                entity.getConsumedByNodeRunId(),
                entity.getCreatedAt(),
                entity.getTargetRepositoryId()
        );
    }

    @Override
    public List<ConnectionResolution> findBySourceNodeRunId(final UUID sourceNodeRunId) {
        return this.repository.findBySourceNodeRunIdOrderByCreatedAtAscIdAsc(sourceNodeRunId).stream()
                .map(PostgresConnectionResolutionRepository::toDomain)
                .toList();
    }

    @Override
    public List<ConnectionResolution> findConsumedByNodeRunId(final UUID nodeRunId) {
        return this.repository.findByConsumedByNodeRunIdOrderByCreatedAtAscIdAsc(nodeRunId).stream()
                .map(PostgresConnectionResolutionRepository::toDomain)
                .toList();
    }

    @Override
    public void saveAll(final Collection<ConnectionResolution> resolutions) {
        this.repository.saveAll(resolutions.stream().map(this::toEntity).toList());
    }

    @Override
    public int markConsumed(final Collection<UUID> resolutionIds, final UUID nodeRunId) {
        if (resolutionIds == null || resolutionIds.isEmpty()) {
            return 0;
        }
        return this.repository.markConsumed(resolutionIds, nodeRunId);
    }

    private ConnectionResolutionEntity toEntity(final ConnectionResolution resolution) {
        final ConnectionResolutionEntity entity = new ConnectionResolutionEntity();
        entity.setId(resolution.id());
        entity.setWorkflowRunId(resolution.workflowRunId());
        entity.setExecutionFrameId(resolution.executionFrameId());
        entity.setSourceNodeRunId(resolution.sourceNodeRunId());
        entity.setSourceConnectionId(resolution.sourceConnectionId());
        entity.setTargetInputPortId(resolution.targetInputPortId());
        entity.setTargetRepositoryId(resolution.targetRepositoryId());
        entity.setResolutionType(resolution.type().name());
        entity.setPayload(resolution.payload() == null ? null : resolution.payload().jsonValue());
        entity.setConsumedByNodeRunId(resolution.consumedByNodeRunId());
        entity.setCreatedAt(resolution.createdAt());
        return entity;
    }
}
