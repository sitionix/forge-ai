package com.sitionix.forgeai.domain.model.operator.read;

import java.util.UUID;

public record OperatorUiLaneDependency(
        String agent,
        String scope,
        UUID laneId,
        String status
) {
}
