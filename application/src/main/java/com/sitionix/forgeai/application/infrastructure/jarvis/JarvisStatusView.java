package com.sitionix.forgeai.application.infrastructure.jarvis;

public record JarvisStatusView(
        String status,
        String host,
        Integer port,
        JarvisModelView model,
        JarvisRuntimeView ollama,
        JarvisActionsSummaryView actions
) {
}
