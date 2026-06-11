package com.sitionix.forgeai.infrastructure.knowledgesqlite.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class KnowledgeInventoryBuildEntity {

    private Long id;
    private String startedAt;
    private String completedAt;
    private String status;
    private Integer sourceCount;
    private Integer fileCount;
    private Integer skippedCount;
    private String errorMessage;
}
