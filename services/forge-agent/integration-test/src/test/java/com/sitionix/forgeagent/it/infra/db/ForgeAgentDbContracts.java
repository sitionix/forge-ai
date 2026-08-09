package com.sitionix.forgeagent.it.infra.db;

import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDefinitionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDependencyEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectEntity;
import com.sitionix.forgeit.core.contract.ForgeDbContracts;
import com.sitionix.forgeit.domain.contract.DbContract;
import com.sitionix.forgeit.domain.contract.DbContractsDsl;
import com.sitionix.forgeit.domain.contract.clean.CleanupPolicy;

@ForgeDbContracts
public final class ForgeAgentDbContracts {

    private ForgeAgentDbContracts() {
    }

    public static final DbContract<AgentDependencyEntity> AGENT_DEPENDENCY =
            DbContractsDsl.entity(AgentDependencyEntity.class)
                    .cleanupPolicy(CleanupPolicy.DELETE_ALL)
                    .build();

    public static final DbContract<AgentDefinitionEntity> AGENT_DEFINITION =
            DbContractsDsl.entity(AgentDefinitionEntity.class)
                    .cleanupPolicy(CleanupPolicy.DELETE_ALL)
                    .build();

    public static final DbContract<ProjectEntity> PROJECT =
            DbContractsDsl.entity(ProjectEntity.class)
                    .cleanupPolicy(CleanupPolicy.DELETE_ALL)
                    .build();
}
