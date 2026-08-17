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
@Table(name = "project_repositories")
@Getter
@Setter
public class ProjectRepositoryEntity {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "remote_url", nullable = false, columnDefinition = "TEXT")
    private String remoteUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
