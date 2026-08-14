package com.sitionix.forgeagent.it.infra.db;

import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDefinitionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.NodeRunEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectTaskEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowConnectionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowNodeEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.WorkflowRunEntity;
import com.sitionix.forgeit.core.contract.ForgeDbContracts;
import com.sitionix.forgeit.domain.contract.DbContract;
import com.sitionix.forgeit.domain.contract.DbContractsDsl;
import com.sitionix.forgeit.domain.contract.clean.CleanupPolicy;

@ForgeDbContracts
public final class ForgeAgentDbContracts {

    private ForgeAgentDbContracts() {
    }

    public static final DbContract<WorkflowNodeEntity> WORKFLOW_NODE =
            DbContractsDsl.entity(WorkflowNodeEntity.class)
                    .cleanupPolicy(CleanupPolicy.DELETE_ALL)
                    .build();

    public static final DbContract<WorkflowConnectionEntity> WORKFLOW_CONNECTION =
            DbContractsDsl.entity(WorkflowConnectionEntity.class)
                    .cleanupPolicy(CleanupPolicy.DELETE_ALL)
                    .build();

    public static final DbContract<NodeRunEntity> NODE_RUN =
            DbContractsDsl.entity(NodeRunEntity.class)
                    .cleanupPolicy(CleanupPolicy.DELETE_ALL)
                    .build();

    public static final DbContract<WorkflowRunEntity> WORKFLOW_RUN =
            DbContractsDsl.entity(WorkflowRunEntity.class)
                    .cleanupPolicy(CleanupPolicy.DELETE_ALL)
                    .build();

    public static final DbContract<ProjectTaskEntity> PROJECT_TASK =
            DbContractsDsl.entity(ProjectTaskEntity.class)
                    .cleanupPolicy(CleanupPolicy.DELETE_ALL)
                    .build();

    public static final DbContract<WorkflowEntity> WORKFLOW =
            DbContractsDsl.entity(WorkflowEntity.class)
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
