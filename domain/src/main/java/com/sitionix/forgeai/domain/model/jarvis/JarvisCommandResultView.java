package com.sitionix.forgeai.domain.model.jarvis;

public record JarvisCommandResultView(
        String input,
        JarvisIntentView intent,
        JarvisExecutionView execution
) {
}
