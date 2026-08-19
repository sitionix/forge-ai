package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "project_tasks")
@Getter
@Setter
public class ProjectTaskEntity {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false)
    private String input;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "project_task_repositories", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "repository_id", nullable = false)
    @OrderColumn(name = "repository_ordinal")
    private List<UUID> repositoryIds = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
