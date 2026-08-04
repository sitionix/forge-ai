package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.AgentDependency;
import com.sitionix.forgeagent.domain.port.AgentDependencyRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDependencyEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDependencyId;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataAgentDependencyRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
        this.repository.deleteByAgentId(agentId);
        if (dependsOnAgentIds == null || dependsOnAgentIds.isEmpty()) {
            return;
        }
        this.repository.saveAll(dependsOnAgentIds.stream()
                .map(dependsOnAgentId -> new AgentDependencyEntity(new AgentDependencyId(agentId, dependsOnAgentId)))
                .toList());
    }

    private AgentDependency toDomain(final AgentDependencyEntity entity) {
        return new AgentDependency(entity.getId().getAgentId(), entity.getId().getDependsOnAgentId());
    }
}
