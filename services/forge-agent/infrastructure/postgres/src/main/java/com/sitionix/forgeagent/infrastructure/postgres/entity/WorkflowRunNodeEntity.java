package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_run_nodes")
@IdClass(WorkflowRunNodeEntityId.class)
@Getter
@Setter
public class WorkflowRunNodeEntity {

    @Id
    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Id
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

    @Column(name = "execution_model_provider_id", nullable = false, length = 120)
    private String executionModelProviderId;

    @Column(name = "execution_model_id", nullable = false, length = 240)
    private String executionModelId;

    @Column(name = "execution_model_effort_id", length = 120)
    private String executionModelEffortId;

    @Column(name = "input_mode", nullable = false, length = 32)
    private String inputMode;

    @Column(name = "position_x", nullable = false)
    private double positionX;

    @Column(name = "position_y", nullable = false)
    private double positionY;
}
