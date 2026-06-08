package com.sitionix.forgeai.domain.model.operator.service;

public record OperatorServiceActionResponse(
        String serviceId,
        String status,
        String message,
        OperatorServiceSummary service
) {
}
