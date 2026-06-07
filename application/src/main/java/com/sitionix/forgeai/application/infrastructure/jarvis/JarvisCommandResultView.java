package com.sitionix.forgeai.application.infrastructure.jarvis;

public record JarvisCommandResultView(
        String input,
        JarvisIntentView intent,
        JarvisExecutionView execution
) {
}
