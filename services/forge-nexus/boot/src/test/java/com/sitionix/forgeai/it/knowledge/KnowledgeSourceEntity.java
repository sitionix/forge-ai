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
@Table(name = "sources")
public class KnowledgeSourceEntity {

    @Id
    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "group_name")
    private String groupName;

    @Column(nullable = false)
    private String path;

    @Column(name = "root_exists", nullable = false)
    private Integer rootExists;

    @Column(name = "tags_json", nullable = false)
    private String tagsJson;

    @Column(name = "metadata_json", nullable = false)
    private String metadataJson;

    @Column(name = "last_seen_at", nullable = false)
    private String lastSeenAt;
}
