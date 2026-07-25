package com.sitionix.forgeai.domain.model.operator.config;

import java.util.List;

public record OperatorLaneStrategyView(
        String agentId,
        int version,
        String sessionMode,
        List<OperatorLaneStrategyStepView> steps
) {
}
