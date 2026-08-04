package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.AgentDependency;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AgentDependencyRepository {

    List<AgentDependency> findByProjectId(UUID projectId);

    List<UUID> findDependsOnIds(UUID agentId);

    void replaceDependencies(UUID agentId, Collection<UUID> dependsOnAgentIds);
}
