package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.InputActivationResolution;
import com.sitionix.forgeagent.domain.port.InputActivationResolutionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.InputActivationResolutionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataInputActivationResolutionRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresInputActivationResolutionRepository implements InputActivationResolutionRepository {

    private final SpringDataInputActivationResolutionRepository repository;

    @Override
    public InputActivationResolution save(final InputActivationResolution resolution) {
        return this.toDomain(this.repository.save(this.toEntity(resolution)));
    }

    @Override
    public Optional<InputActivationResolution> find(final UUID workflowRunId, final UUID activationFrameId,
                                                    final UUID targetInputPortId, final UUID repositoryId) {
        final Optional<InputActivationResolutionEntity> found = repositoryId == null
                ? this.repository.findByWorkflowRunIdAndActivationFrameIdAndTargetInputPortIdAndRepositoryIdIsNull(workflowRunId, activationFrameId, targetInputPortId)
                : this.repository.findByWorkflowRunIdAndActivationFrameIdAndTargetInputPortIdAndRepositoryId(workflowRunId, activationFrameId, targetInputPortId, repositoryId);
        return found
                .map(this::toDomain);
    }

    private InputActivationResolution toDomain(final InputActivationResolutionEntity entity) {
        return new InputActivationResolution(
                entity.getId(),
                entity.getWorkflowRunId(),
                entity.getActivationFrameId(),
                entity.getTargetInputPortId(),
                entity.getActivatedNodeRunId(),
                entity.getCreatedAt(),
                entity.getRepositoryId()
        );
    }

    private InputActivationResolutionEntity toEntity(final InputActivationResolution resolution) {
        final InputActivationResolutionEntity entity = new InputActivationResolutionEntity();
        entity.setId(resolution.id());
        entity.setWorkflowRunId(resolution.workflowRunId());
        entity.setActivationFrameId(resolution.activationFrameId());
        entity.setTargetInputPortId(resolution.targetInputPortId());
        entity.setRepositoryId(resolution.repositoryId());
        entity.setActivatedNodeRunId(resolution.activatedNodeRunId());
        entity.setCreatedAt(resolution.createdAt());
        return entity;
    }
}
