package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeSearchRequest(String query, List<String> sourceIds, List<String> groups, Integer limit) {
}
