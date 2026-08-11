package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDefinitionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataAgentDefinitionRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresAgentDefinitionRepository implements AgentDefinitionRepository {

    private final SpringDataAgentDefinitionRepository repository;

    @Override
    public Optional<AgentDefinition> findById(final UUID agentId) {
        return this.repository.findById(agentId).map(this::toDomain);
    }

    @Override
    public List<AgentDefinition> findByIds(final Collection<UUID> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return List.of();
        }
        return this.repository.findAllById(agentIds).stream().map(this::toDomain).toList();
    }

    @Override
    public List<AgentDefinition> findByProjectId(final UUID projectId) {
        return this.repository.findByProjectIdOrderByNormalizedNameAscIdAsc(projectId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean existsByProjectIdAndNormalizedName(final UUID projectId, final String normalizedName) {
        return this.repository.existsByProjectIdAndNormalizedName(projectId, normalizedName);
    }

    @Override
    public boolean existsByProjectIdAndNormalizedNameExcludingId(final UUID projectId,
                                                                final String normalizedName,
                                                                final UUID excludedAgentId) {
        return this.repository.existsByProjectIdAndNormalizedNameAndIdNot(projectId, normalizedName, excludedAgentId);
    }

    @Override
    public AgentDefinition save(final AgentDefinition agentDefinition) {
        return this.toDomain(this.repository.save(this.toEntity(agentDefinition)));
    }

    private AgentDefinition toDomain(final AgentDefinitionEntity entity) {
        return new AgentDefinition(
                entity.getId(),
                entity.getProjectId(),
                entity.getName(),
                entity.getNormalizedName(),
                entity.getInstructions(),
                AgentOutputSchema.ofCanonicalJsonObject(entity.getOutputSchema()),
                this.toModelSelection(entity),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private AgentDefinitionEntity toEntity(final AgentDefinition agentDefinition) {
        final AgentDefinitionEntity entity = new AgentDefinitionEntity();
        entity.setId(agentDefinition.id());
        entity.setProjectId(agentDefinition.projectId());
        entity.setName(agentDefinition.name());
        entity.setNormalizedName(agentDefinition.normalizedName());
        entity.setInstructions(agentDefinition.instructions());
        entity.setOutputSchema(agentDefinition.outputSchema().jsonObject());
        if (agentDefinition.model() != null) {
            entity.setModelProviderId(agentDefinition.model().providerId());
            entity.setModelId(agentDefinition.model().modelId());
            entity.setModelEffortId(agentDefinition.model().effortId());
        }
        entity.setCreatedAt(agentDefinition.createdAt());
        entity.setUpdatedAt(agentDefinition.updatedAt());
        return entity;
    }

    private AgentModelSelection toModelSelection(final AgentDefinitionEntity entity) {
        if (entity.getModelProviderId() == null && entity.getModelId() == null && entity.getModelEffortId() == null) {
            return null;
        }
        return new AgentModelSelection(entity.getModelProviderId(), entity.getModelId(), entity.getModelEffortId());
    }
}
