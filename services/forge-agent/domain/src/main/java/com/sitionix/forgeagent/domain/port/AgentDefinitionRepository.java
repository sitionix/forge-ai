package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.AgentDefinition;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentDefinitionRepository {

    Optional<AgentDefinition> findById(UUID agentId);

    List<AgentDefinition> findByIds(Collection<UUID> agentIds);

    List<AgentDefinition> findByProjectId(UUID projectId);

    boolean existsByProjectIdAndNormalizedName(UUID projectId, String normalizedName);

    boolean existsByProjectIdAndNormalizedNameExcludingId(UUID projectId, String normalizedName, UUID excludedAgentId);

    AgentDefinition save(AgentDefinition agentDefinition);
}
