package com.sitionix.forgeai.domain.model.operator.config;

public record OperatorAgentCompletionView(
        boolean writesProducedLaneOutputs,
        boolean requiresApiEvidence,
        boolean requiresOutputForEveryTarget,
        String reportPayload
) {
}
