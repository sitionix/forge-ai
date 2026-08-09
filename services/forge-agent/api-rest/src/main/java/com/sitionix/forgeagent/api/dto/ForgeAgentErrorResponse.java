package com.sitionix.forgeagent.api.dto;

public record ForgeAgentErrorResponse(String code, String message, String correlationId) {
}
