package com.sitionix.forgeai.domain.model.operator.config;

public record OperatorConfigResourceView(
        String resourceKey,
        String label,
        String resourceType,
        String path,
        boolean writable,
        String content
) {
}
