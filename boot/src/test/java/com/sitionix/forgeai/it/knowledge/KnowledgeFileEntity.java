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
@Table(name = "files")
public class KnowledgeFileEntity {

    @Id
    private Long id;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(name = "source_path", nullable = false)
    private String sourcePath;

    @Column(name = "absolute_path", nullable = false)
    private String absolutePath;

    @Column(name = "relative_path", nullable = false)
    private String relativePath;

    private String extension;

    private String language;

    @Column(name = "flow_domain")
    private String flowDomain;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "last_modified", nullable = false)
    private String lastModified;

    @Column(name = "line_count", nullable = false)
    private Long lineCount;

    @Column(name = "decode_policy", nullable = false)
    private String decodePolicy;

    @Column(name = "indexed_at", nullable = false)
    private String indexedAt;
}
