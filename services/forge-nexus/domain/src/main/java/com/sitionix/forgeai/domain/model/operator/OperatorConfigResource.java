package com.sitionix.forgeai.domain.model.operator;

import lombok.Builder;

@Builder
public record OperatorConfigResource(
        String resourceKey,
        String label,
        String resourceType,
        String path,
        boolean writable,
        String content
) {
}
