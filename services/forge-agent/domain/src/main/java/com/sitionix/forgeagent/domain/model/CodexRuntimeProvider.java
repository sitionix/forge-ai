package com.sitionix.forgeagent.domain.model;

import java.util.List;

public record CodexRuntimeProvider(
        String providerId,
        String displayName,
        RuntimeProviderStatus status,
        String version,
        List<CodexRuntimeModel> models
) {
    public CodexRuntimeProvider {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
