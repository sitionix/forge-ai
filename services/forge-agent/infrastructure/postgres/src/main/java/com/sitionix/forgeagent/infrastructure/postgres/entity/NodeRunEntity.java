package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "node_runs",
        uniqueConstraints = @UniqueConstraint(name = "uk_node_runs_workflow_run_source_node", columnNames = {"workflow_run_id", "source_node_id"})
)
@Getter
@Setter
public class NodeRunEntity {

    @Id
    private UUID id;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "source_node_id", nullable = false)
    private UUID sourceNodeId;

    @Column(name = "source_agent_id", nullable = false)
    private UUID sourceAgentId;

    @Column(name = "agent_name", nullable = false, length = 120)
    private String agentName;

    @Column(name = "agent_instructions", nullable = false)
    private String agentInstructions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_output_schema", nullable = false, columnDefinition = "jsonb")
    private String agentOutputSchema;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "depends_on_node_run_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] dependsOnNodeRunIds;

    @Column(name = "position_x", nullable = false)
    private double positionX;

    @Column(name = "position_y", nullable = false)
    private double positionY;

    @Column(nullable = false, length = 32)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String output;

    @Column(name = "failure_code", length = 120)
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "execution_model_provider_id", length = 120)
    private String executionModelProviderId;

    @Column(name = "execution_model_id", length = 240)
    private String executionModelId;

    @Column(name = "execution_model_effort_id", length = 120)
    private String executionModelEffortId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
