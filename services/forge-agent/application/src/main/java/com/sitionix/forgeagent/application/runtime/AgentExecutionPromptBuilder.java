package com.sitionix.forgeagent.application.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class AgentExecutionPromptBuilder {

    private static final String TEMPLATE = loadTemplate();

    private final AgentDependencyContextRenderer dependencyContextRenderer;

    public String build(final NodeExecutionClaim claim) {
        return TEMPLATE
                .replace("${agentInstructions}", this.value(claim.agentInstructions()))
                .replace("${workflowInput}", this.value(claim.workflowInput()))
                .replace("${dependencyResults}", this.dependencyContextRenderer.render(claim.dependencies()));
    }

    private String value(final String value) {
        return value == null ? "" : value;
    }

    private static String loadTemplate() {
        try (var stream = AgentExecutionPromptBuilder.class.getResourceAsStream("/prompts/agent-execution.txt")) {
            if (stream == null) {
                throw new IllegalStateException("Agent execution prompt template is missing.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            throw new UncheckedIOException("Agent execution prompt template could not be read.", exception);
        }
    }
}
