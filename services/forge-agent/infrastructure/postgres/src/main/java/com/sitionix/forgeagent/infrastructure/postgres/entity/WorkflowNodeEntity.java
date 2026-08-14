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
@Table(name = "workflow_nodes")
@IdClass(WorkflowNodeEntityId.class)
@Getter
@Setter
public class WorkflowNodeEntity {

    @Id
    private UUID id;

    @Id
    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "input_mode", nullable = false, length = 32)
    private String inputMode = "DEPENDENCIES_ONLY";

    @Column(name = "position_x", nullable = false)
    private double positionX;

    @Column(name = "position_y", nullable = false)
    private double positionY;
}
