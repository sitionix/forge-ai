package com.sitionix.forgeai.domain.model.operator.config;

import java.util.List;

public record OperatorLaneStrategyStepView(
        int order,
        String id,
        String title,
        String type,
        String handler,
        String taskPlaceholder,
        String completionContractPlaceholder,
        List<String> instructionRefs
) {
}
