package com.sitionix.forgeagent.domain.model;

import java.util.List;

public record CodexRuntimeModel(
        String modelId,
        String displayName,
        String description,
        List<CodexRuntimeEffort> efforts
) {
    public CodexRuntimeModel {
        efforts = efforts == null ? List.of() : List.copyOf(efforts);
    }
}
