package com.sitionix.forgeai.domain.model.operator.read;

public record OperatorUiLaneCounts(
        long notStarted,
        long ready,
        long inProgress,
        long completed,
        long notNeeded
) {
}
