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
@Table(name = "workflow_runs")
@Getter
@Setter
public class WorkflowRunEntity {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "source_workflow_id", nullable = false)
    private UUID sourceWorkflowId;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "task_input_port_id")
    private UUID taskInputPortId;

    @Column(name = "task_output_port_id")
    private UUID taskOutputPortId;

    @Column(name = "workflow_name", nullable = false, length = 120)
    private String workflowName;

    @Column(nullable = false)
    private String input;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    @Column(name = "result_source_node_run_id")
    private UUID resultSourceNodeRunId;
}
