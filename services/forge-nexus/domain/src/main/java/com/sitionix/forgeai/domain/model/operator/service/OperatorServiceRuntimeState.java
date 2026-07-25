package com.sitionix.forgeai.domain.model.operator.service;

public record OperatorServiceRuntimeState(
        String status,
        String containerName,
        String message
) {
}
