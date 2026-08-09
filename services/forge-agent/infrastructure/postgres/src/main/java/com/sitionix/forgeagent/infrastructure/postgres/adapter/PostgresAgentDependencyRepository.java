package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.AgentDependency;
import com.sitionix.forgeagent.domain.port.AgentDependencyRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDependencyEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDependencyId;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataAgentDependencyRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresAgentDependencyRepository implements AgentDependencyRepository {

    private final SpringDataAgentDependencyRepository repository;

    @Override
    public List<AgentDependency> findByProjectId(final UUID projectId) {
        return this.repository.findByProjectId(projectId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<UUID> findDependsOnIds(final UUID agentId) {
        return this.repository.findByIdAgentId(agentId).stream()
                .map(entity -> entity.getId().getDependsOnAgentId())
                .toList();
    }

    @Override
    public void replaceDependencies(final UUID agentId, final Collection<UUID> dependsOnAgentIds) {
        final List<AgentDependencyEntity> currentDependencies = this.repository.findByIdAgentId(agentId);
        final Set<UUID> desiredDependencyIds = dependsOnAgentIds == null
                ? Set.of()
                : dependsOnAgentIds.stream().collect(Collectors.toCollection(LinkedHashSet::new));
        final Set<UUID> currentDependencyIds = currentDependencies.stream()
                .map(entity -> entity.getId().getDependsOnAgentId())
                .collect(Collectors.toSet());

        final List<AgentDependencyEntity> toDelete = currentDependencies.stream()
                .filter(entity -> !desiredDependencyIds.contains(entity.getId().getDependsOnAgentId()))
                .toList();
        if (!toDelete.isEmpty()) {
            this.repository.deleteAll(toDelete);
        }

        final List<AgentDependencyEntity> toInsert = desiredDependencyIds.stream()
                .filter(dependsOnAgentId -> !currentDependencyIds.contains(dependsOnAgentId))
                .map(dependsOnAgentId -> new AgentDependencyEntity(new AgentDependencyId(agentId, dependsOnAgentId)))
                .toList();
        if (!toInsert.isEmpty()) {
            this.repository.saveAll(toInsert);
        }
    }

    private AgentDependency toDomain(final AgentDependencyEntity entity) {
        return new AgentDependency(entity.getId().getAgentId(), entity.getId().getDependsOnAgentId());
    }
}
