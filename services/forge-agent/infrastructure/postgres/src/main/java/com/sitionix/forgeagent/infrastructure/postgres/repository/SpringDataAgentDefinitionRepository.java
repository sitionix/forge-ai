package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDefinitionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataAgentDefinitionRepository extends JpaRepository<AgentDefinitionEntity, UUID> {

    List<AgentDefinitionEntity> findByProjectIdOrderByNormalizedNameAscIdAsc(UUID projectId);

    boolean existsByProjectIdAndNormalizedName(UUID projectId, String normalizedName);

    boolean existsByProjectIdAndNormalizedNameAndIdNot(UUID projectId, String normalizedName, UUID id);
}
