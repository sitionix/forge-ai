package com.sitionix.forgeai.infrastructure.knowledgesqlite.repository;

import lombok.Value;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Value
@Accessors(fluent = true)
class KnowledgeSqlFilter {

    String where;
    List<Object> params;

    static KnowledgeSqlFilter files(final String sourceId, final String pathContains, final String extension) {
        final List<String> clauses = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        if (sourceId != null && !sourceId.isBlank()) {
            clauses.add("f.source_id = ?");
            params.add(sourceId);
        }
        if (pathContains != null && !pathContains.isBlank()) {
            clauses.add("f.relative_path LIKE ?");
            params.add("%" + pathContains + "%");
        }
        if (extension != null && !extension.isBlank()) {
            clauses.add("f.extension = ?");
            params.add(extension.startsWith(".") ? extension : "." + extension);
        }
        return where(clauses, params);
    }

    private static KnowledgeSqlFilter where(final List<String> clauses, final List<Object> params) {
        return new KnowledgeSqlFilter(clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses), params);
    }
}
