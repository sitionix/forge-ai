package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDependencyEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDependencyId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataAgentDependencyRepository extends JpaRepository<AgentDependencyEntity, AgentDependencyId> {

    @Query("""
            select dependency
            from AgentDependencyEntity dependency
            join AgentDefinitionEntity agent on agent.id = dependency.id.agentId
            where agent.projectId = :projectId
            """)
    List<AgentDependencyEntity> findByProjectId(@Param("projectId") UUID projectId);

    List<AgentDependencyEntity> findByIdAgentId(UUID agentId);
}
