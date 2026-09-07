package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "node_runs")
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

    @Column(name = "position_x", nullable = false)
    private double positionX;

    @Column(name = "position_y", nullable = false)
    private double positionY;

    @Column(name = "input_mode", nullable = false, length = 32)
    private String inputMode = "DEPENDENCIES_ONLY";

    @Column(name = "execution_frame_id", nullable = false)
    private UUID executionFrameId;

    @Column(name = "repository_id")
    private UUID repositoryId;

    @Column(name = "context_mode", nullable = false, length = 48)
    private String contextMode;

    @Column(name = "context_tracking_version")
    private Integer contextTrackingVersion;

    @Column(name = "entered_via_input_port_id")
    private UUID enteredViaInputPortId;

    @Column(name = "activation_frame_id")
    private UUID activationFrameId;

    @Column(name = "selected_output_port_id")
    private UUID selectedOutputPortId;

    @Column(name = "routing_completed_at")
    private Instant routingCompletedAt;

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
