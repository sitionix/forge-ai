package com.sitionix.forgeai.infrastructure.knowledgesqlite.adapter;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Value
@Builder
@Jacksonized
public class KnowledgeSourceMetadata {

    String sourceId;
    String displayName;
    String group;
    String path;
    Boolean rootExists;
    @Builder.Default
    List<String> tags = List.of();
    @Builder.Default
    List<String> domainKeywords = List.of();
    @Builder.Default
    List<String> ownsBusinessAreas = List.of();
    String absoluteRoot;
}
