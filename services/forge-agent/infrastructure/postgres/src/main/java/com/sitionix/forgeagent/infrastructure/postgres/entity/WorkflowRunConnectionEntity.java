package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "workflow_run_connections")
@IdClass(WorkflowRunConnectionEntityId.class)
@Getter
@Setter
public class WorkflowRunConnectionEntity {

    @Id
    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Id
    @Column(name = "source_connection_id", nullable = false)
    private UUID sourceConnectionId;

    @Column(name = "source_output_port_id", nullable = false)
    private UUID sourceOutputPortId;

    @Column(name = "target_input_port_id", nullable = false)
    private UUID targetInputPortId;
}
