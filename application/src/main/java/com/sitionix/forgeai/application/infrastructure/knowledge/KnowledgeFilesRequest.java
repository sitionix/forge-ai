package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeFilesRequest(String sourceId, String pathContains, String extension, Integer limit, Integer offset) {
}
