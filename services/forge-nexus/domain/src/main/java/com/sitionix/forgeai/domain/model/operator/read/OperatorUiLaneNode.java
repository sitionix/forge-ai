package com.sitionix.forgeai.domain.model.operator.read;

import java.util.List;
import java.util.UUID;

public record OperatorUiLaneNode(
        UUID laneId,
        String agent,
        String scope,
        String serviceId,
        String status,
        int attempt,
        int inputTaskCount,
        List<OperatorUiLaneDependency> dependencies,
        OperatorUiLaneExecution execution
) {
}
