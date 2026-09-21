package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import java.util.List;
import java.util.ArrayList;
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

    @Column(name = "node_type", nullable = false, length = 16)
    private String nodeType;

    @Id
    private UUID id;

    @Id
    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "input_mode", nullable = false, length = 32)
    private String inputMode = "DEPENDENCIES_ONLY";

    @Column(name = "scope_mode", nullable = false, length = 32)
    private String scopeMode;

    @Column(name = "context_mode", nullable = false, length = 48)
    private String contextMode;

    @Column(name = "context_group_key")
    private String contextGroupKey;

    @Column(name = "position_x", nullable = false)
    private double positionX;

    @Column(name = "position_y", nullable = false)
    private double positionY;

    @Column(name = "include_task_repositories", nullable = false)
    private Boolean includeTaskRepositories;

    @ElementCollection
    @CollectionTable(name = "workflow_node_workspace_repositories", joinColumns = {
            @JoinColumn(name = "workflow_id", referencedColumnName = "workflow_id"),
            @JoinColumn(name = "node_id", referencedColumnName = "id")})
    @OrderColumn(name = "repository_ordinal")
    @Column(name = "repository_id", nullable = false)
    private List<UUID> workspaceRepositoryIds = new ArrayList<>();
}
