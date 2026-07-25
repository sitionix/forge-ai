package com.sitionix.forgeai.domain.model.operator.config;

public record OperatorPayloadContractSummary(
        String payloadType,
        String payloadClass,
        String description,
        String resourceKey
) {
}
