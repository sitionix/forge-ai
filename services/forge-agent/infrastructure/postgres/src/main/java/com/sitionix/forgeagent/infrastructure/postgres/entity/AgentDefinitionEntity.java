package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "agent_definitions",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_definitions_project_normalized_name", columnNames = {"project_id", "normalized_name"})
)
@Getter
@Setter
public class AgentDefinitionEntity {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 120)
    private String normalizedName;

    @Column(nullable = false)
    private String instructions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_schema", nullable = false, columnDefinition = "jsonb")
    private String outputSchema;

    @Column(name = "model_provider_id")
    private String modelProviderId;

    @Column(name = "model_id")
    private String modelId;

    @Column(name = "model_effort_id")
    private String modelEffortId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
