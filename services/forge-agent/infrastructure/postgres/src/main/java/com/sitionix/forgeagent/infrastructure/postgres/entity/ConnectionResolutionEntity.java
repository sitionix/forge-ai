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
@Table(name = "workflow_connection_resolutions")
@Getter
@Setter
public class ConnectionResolutionEntity {

    @Id
    private UUID id;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "execution_frame_id", nullable = false)
    private UUID executionFrameId;

    @Column(name = "source_node_run_id", nullable = false)
    private UUID sourceNodeRunId;

    @Column(name = "source_connection_id", nullable = false)
    private UUID sourceConnectionId;

    @Column(name = "target_input_port_id", nullable = false)
    private UUID targetInputPortId;

    @Column(name = "resolution_type", nullable = false, length = 32)
    private String resolutionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "consumed_by_node_run_id")
    private UUID consumedByNodeRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
