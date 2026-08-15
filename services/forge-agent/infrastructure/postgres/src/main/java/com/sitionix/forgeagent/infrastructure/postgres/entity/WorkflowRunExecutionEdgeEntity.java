package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "workflow_run_execution_edges")
@IdClass(WorkflowRunExecutionEdgeEntityId.class)
@Getter
@Setter
public class WorkflowRunExecutionEdgeEntity {

    @Id
    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Id
    @Column(name = "source_node_run_id", nullable = false)
    private UUID sourceNodeRunId;

    @Id
    @Column(name = "target_node_run_id", nullable = false)
    private UUID targetNodeRunId;

    @Id
    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;
}
