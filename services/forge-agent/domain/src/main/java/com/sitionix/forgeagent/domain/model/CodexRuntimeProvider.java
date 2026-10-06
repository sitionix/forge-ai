package com.sitionix.forgeagent.domain.model;

import java.util.List;

public record CodexRuntimeProvider(
        String providerId,
        String displayName,
        RuntimeProviderStatus status,
        String version,
        List<CodexRuntimeModel> models,
        String authState
) {
    public CodexRuntimeProvider(String providerId, String displayName, RuntimeProviderStatus status,
            String version, List<CodexRuntimeModel> models) {
        this(providerId, displayName, status, version, models, "SIGNED_OUT");
    }
    public CodexRuntimeProvider {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
