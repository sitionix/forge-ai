package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.RuntimeProviderStatus;
import java.util.List;

public record CodexRuntimeProviderResponse(
        String providerId,
        String displayName,
        RuntimeProviderStatus status,
        String version,
        List<CodexRuntimeModelResponse> models
) {
}
