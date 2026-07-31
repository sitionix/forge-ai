package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeErrorResponse(
        String code,
        String message,
        String correlationId
) {
}
