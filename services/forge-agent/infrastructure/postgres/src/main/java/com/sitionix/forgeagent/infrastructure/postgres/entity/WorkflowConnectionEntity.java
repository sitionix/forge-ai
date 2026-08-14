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
        name = "workflow_connections",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_workflow_connections_port_pair",
                columnNames = {"source_output_port_id", "target_input_port_id"}
        )
)
@Getter
@Setter
public class WorkflowConnectionEntity {

    @Id
    private UUID id;

    @Column(name = "source_output_port_id", nullable = false)
    private UUID sourceOutputPortId;

    @Column(name = "target_input_port_id", nullable = false)
    private UUID targetInputPortId;
}
