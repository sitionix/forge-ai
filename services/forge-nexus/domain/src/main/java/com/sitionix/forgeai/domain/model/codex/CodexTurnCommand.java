package com.sitionix.forgeai.domain.model.codex;

import java.time.Duration;
import lombok.Builder;

@Builder(toBuilder = true)
public record CodexTurnCommand(
        String prompt,
        Duration timeout,
        String promptType,
        String stepId,
        Integer stepOrder,
        String stepTitle
) {
}
