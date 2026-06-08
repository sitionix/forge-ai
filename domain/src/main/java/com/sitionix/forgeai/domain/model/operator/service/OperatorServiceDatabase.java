package com.sitionix.forgeai.domain.model.operator.service;

public record OperatorServiceDatabase(
        boolean required,
        String type,
        String mode,
        String key,
        String runtimeStatus,
        String containerName,
        String message
) {
}
