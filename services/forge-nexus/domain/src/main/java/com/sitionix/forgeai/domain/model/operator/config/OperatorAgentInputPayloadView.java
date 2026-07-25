package com.sitionix.forgeai.domain.model.operator.config;

public record OperatorAgentInputPayloadView(
        String sourceAgent,
        String payloadType,
        String payloadClass
) {
}
