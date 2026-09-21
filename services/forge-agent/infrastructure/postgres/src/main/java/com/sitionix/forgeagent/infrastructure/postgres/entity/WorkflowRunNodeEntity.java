package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import java.util.List;
import java.util.ArrayList;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_run_nodes")
@IdClass(WorkflowRunNodeEntityId.class)
@Getter
@Setter
public class WorkflowRunNodeEntity {

    @Column(name = "node_type", nullable = false, length = 16)
    private String nodeType;

    @Id
    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Id
    @Column(name = "source_node_id", nullable = false)
    private UUID sourceNodeId;

    @Column(name = "source_agent_id")
    private UUID sourceAgentId;

    @Column(name = "agent_name", length = 120)
    private String agentName;

    @Column(name = "agent_instructions")
    private String agentInstructions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_output_schema", columnDefinition = "jsonb")
    private String agentOutputSchema;

    @Column(name = "execution_model_provider_id", length = 120)
    private String executionModelProviderId;

    @Column(name = "execution_model_id", length = 240)
    private String executionModelId;

    @Column(name = "execution_model_effort_id", length = 120)
    private String executionModelEffortId;

    @Column(name = "input_mode", nullable = false, length = 32)
    private String inputMode;

    @Column(name = "scope_mode", nullable = false, length = 32)
    private String scopeMode;

    @Column(name = "context_mode", nullable = false, length = 48)
    private String contextMode;

    @Column(name = "context_group_key")
    private String contextGroupKey;

    @Column(name = "position_x", nullable = false)
    private double positionX;

    @Column(name = "position_y", nullable = false)
    private double positionY;

    @ElementCollection
    @CollectionTable(name = "workflow_run_node_workspace_repositories", joinColumns = {
            @JoinColumn(name = "workflow_run_id", referencedColumnName = "workflow_run_id"),
            @JoinColumn(name = "source_node_id", referencedColumnName = "source_node_id")})
    @OrderColumn(name = "repository_ordinal")
    @Column(name = "repository_id", nullable = false)
    private List<UUID> resolvedWorkspaceRepositoryIds = new ArrayList<>();
}
