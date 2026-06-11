package com.sitionix.forgeai.it.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "inventory_builds")
public class KnowledgeInventoryBuildEntity {

    @Id
    private Long id;

    @Column(name = "started_at", nullable = false)
    private String startedAt;

    @Column(name = "completed_at")
    private String completedAt;

    @Column(nullable = false)
    private String status;

    @Column(name = "source_count", nullable = false)
    private Integer sourceCount;

    @Column(name = "file_count", nullable = false)
    private Integer fileCount;

    @Column(name = "skipped_count", nullable = false)
    private Integer skippedCount;

    @Column(name = "error_message")
    private String errorMessage;
}
