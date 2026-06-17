package com.sitionix.forgeai.application.laneexecution.orchestration;

import java.util.List;

public record ApiArtifactGenerationPayload(
        String scope,
        List<String> consumers
) {
}
