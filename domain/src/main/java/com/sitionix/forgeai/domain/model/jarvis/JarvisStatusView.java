package com.sitionix.forgeai.domain.model.jarvis;

public record JarvisStatusView(
        String status,
        String host,
        Integer port,
        JarvisModelView model,
        JarvisRuntimeView ollama,
        JarvisActionsSummaryView actions
) {
}
