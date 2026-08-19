package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "workflow_input_activation_resolutions")
@Getter
@Setter
public class InputActivationResolutionEntity {

    @Id
    private UUID id;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "activation_frame_id", nullable = false)
    private UUID activationFrameId;

    @Column(name = "target_input_port_id", nullable = false)
    private UUID targetInputPortId;

    @Column(name = "repository_id")
    private UUID repositoryId;

    @Column(name = "activated_node_run_id")
    private UUID activatedNodeRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
