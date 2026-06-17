package com.sitionix.forgeai.infrastructure.knowledgesqlite.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class KnowledgeFileEntity {

    private Long id;
    private String sourceId;
    private String sourcePath;
    private String absolutePath;
    private String relativePath;
    private String extension;
    private String language;
    private String flowDomain;
    private Long sizeBytes;
    private String contentHash;
    private String lastModified;
    private Long lineCount;
    private String decodePolicy;
    private String indexedAt;
    private String displayName;
    private String group;
    private String tagsJson;
    private String metadataJson;
}
