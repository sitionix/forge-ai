package com.sitionix.forgeagent.domain.model;

import java.util.List;

public record AiRuntimeCatalog(
        List<CodexRuntimeProvider> providers
) {
    public AiRuntimeCatalog {
        providers = providers == null ? List.of() : List.copyOf(providers);
    }
}
