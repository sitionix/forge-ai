package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "workflow_node_ports",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_workflow_node_ports_direction_name",
                        columnNames = {"workflow_id", "node_id", "direction", "name"}
                ),
                @UniqueConstraint(
                        name = "uk_workflow_node_ports_direction_order",
                        columnNames = {"workflow_id", "node_id", "direction", "port_order"}
                )
        }
)
@Getter
@Setter
public class WorkflowNodePortEntity {

    @Id
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Column(nullable = false, length = 16)
    private String direction;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(name = "port_order", nullable = false)
    private int portOrder;
}
