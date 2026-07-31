package com.sitionix.forgeai.api.activeprofile;

public record InfrastructureErrorResponse(
        String code,
        String message,
        String correlationId
) {
}
