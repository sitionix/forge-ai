package com.sitionix.forgeagent.api.dto;

import java.util.List;

public record CodexRuntimeModelResponse(
        String modelId,
        String displayName,
        String description,
        List<CodexRuntimeEffortResponse> efforts
) {
}
