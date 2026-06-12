package com.sitionix.forgeai.infrastructure.knowledgesqlite.adapter;

import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeFileEntity;
import lombok.Value;
import lombok.experimental.Accessors;

import java.util.List;

@Value
@Accessors(fluent = true)
public class KnowledgeSqliteScanResult {

    List<KnowledgeFileEntity> files;
    int skipped;
}
