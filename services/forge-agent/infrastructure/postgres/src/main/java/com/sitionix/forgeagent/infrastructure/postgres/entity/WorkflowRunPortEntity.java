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
@Table(name = "workflow_run_ports")
@IdClass(WorkflowRunPortEntityId.class)
@Getter
@Setter
public class WorkflowRunPortEntity {

    @Id
    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Id
    @Column(name = "source_port_id", nullable = false)
    private UUID sourcePortId;

    @Column(name = "source_node_id", nullable = false)
    private UUID sourceNodeId;

    @Column(nullable = false, length = 16)
    private String direction;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(name = "port_order", nullable = false)
    private int portOrder;
}
