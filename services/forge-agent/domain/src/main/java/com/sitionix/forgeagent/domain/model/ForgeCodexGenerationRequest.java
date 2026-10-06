package com.sitionix.forgeagent.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record ForgeCodexGenerationRequest(UUID requestId, String prompt, String modelId,
        String effortId, String responseMode, double timeoutSeconds) {
    public ForgeCodexGenerationRequest {
        if (requestId == null || prompt == null || prompt.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024
                || modelId == null || modelId.isBlank() || modelId.length() > 200
                || effortId != null && (effortId.isBlank() || effortId.length() > 64)
                || !java.util.Set.of("text", "json_object").contains(responseMode == null ? "" : responseMode)
                || !Double.isFinite(timeoutSeconds) || timeoutSeconds <= 0 || timeoutSeconds > 5400)
            throw new IllegalArgumentException("INVALID_REQUEST");
    }
}
