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

    static KnowledgeSqlFilter context(final String query,
                                      final List<String> sourceIds,
                                      final List<String> groups) {
        final List<String> clauses = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        final String likeQuery = "%" + query.toLowerCase() + "%";
        clauses.add("""
                (lower(f.relative_path) LIKE ?
                 OR lower(s.source_id) LIKE ?
                 OR lower(s.display_name) LIKE ?
                 OR lower(s.group_name) LIKE ?
                 OR lower(s.tags_json) LIKE ?
                 OR lower(s.metadata_json) LIKE ?)
                """);
        params.add(likeQuery);
        params.add(likeQuery);
        params.add(likeQuery);
        params.add(likeQuery);
        params.add(likeQuery);
        params.add(likeQuery);
        if (sourceIds != null && !sourceIds.isEmpty()) {
            clauses.add("f.source_id IN (%s)".formatted("?,".repeat(sourceIds.size()).replaceAll(",$", "")));
            params.addAll(sourceIds);
        }
        if (groups != null && !groups.isEmpty()) {
            clauses.add("s.group_name IN (%s)".formatted("?,".repeat(groups.size()).replaceAll(",$", "")));
            params.addAll(groups);
        }
        return where(clauses, params);
    }

    private static KnowledgeSqlFilter where(final List<String> clauses, final List<Object> params) {
        return new KnowledgeSqlFilter(clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses), params);
    }
}
