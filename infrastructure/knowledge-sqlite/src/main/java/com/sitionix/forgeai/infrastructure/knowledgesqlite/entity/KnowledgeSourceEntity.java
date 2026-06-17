package com.sitionix.forgeai.infrastructure.knowledgesqlite.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class KnowledgeSourceEntity {

    private String sourceId;
    private String displayName;
    private String group;
    private String path;
    private Boolean rootExists;
    private String tagsJson;
    private String metadataJson;
    private String lastSeenAt;
}
