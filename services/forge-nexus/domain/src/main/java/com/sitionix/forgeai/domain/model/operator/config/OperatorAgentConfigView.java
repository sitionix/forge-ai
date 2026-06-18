package com.sitionix.forgeai.domain.model.operator.config;

import java.util.List;

public record OperatorAgentConfigView(
        String id,
        boolean enabled,
        String scopeMode,
        List<String> groups,
        List<String> dependsOn,
        List<String> produces,
        List<OperatorAgentInputPayloadView> inputPayloads,
        OperatorAgentCompletionView completion,
        OperatorLaneStrategyView laneStrategy,
        List<OperatorPayloadContractSummary> payloadContracts
) {
}
