package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.RuntimeProviderStatus;
import java.util.List;

public record CodexRuntimeProviderResponse(
        String providerId,
        String displayName,
        RuntimeProviderStatus status,
        String version,
        List<CodexRuntimeModelResponse> models,
        String authState
) {
    public CodexRuntimeProviderResponse(String providerId, String displayName, RuntimeProviderStatus status,
            String version, List<CodexRuntimeModelResponse> models) {
        this(providerId, displayName, status, version, models, "SIGNED_OUT");
    }
}
