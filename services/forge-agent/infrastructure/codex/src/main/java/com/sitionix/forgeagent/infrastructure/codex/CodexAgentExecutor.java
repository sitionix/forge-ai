package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.NodeDependencyOutput;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class CodexAgentExecutor implements AgentExecutor {

    private static final String PROVIDER_ID = "codex";

    private final ObjectMapper objectMapper;
    private final CodexTurnClient turnClient;

    @Override
    public NodeRunOutput execute(final NodeExecutionClaim claim) {
        if (!PROVIDER_ID.equals(claim.executionModel().providerId())) {
            throw new IllegalStateException("Agent provider is not supported.");
        }
        final JsonNode outputSchema = this.parseOutputSchema(claim);
        final String outputText = this.turnClient.execute(new CodexTurnRequest(
                this.userInput(claim),
                claim.agentInstructions(),
                claim.executionModel().modelId(),
                claim.executionModel().effortId(),
                outputSchema
        ));
        return new NodeRunOutput(this.canonicalizeOutput(outputText));
    }

    private String userInput(final NodeExecutionClaim claim) {
        try {
            return this.objectMapper.writeValueAsString(new CodexAgentInput(
                    claim.workflowInput(),
                    this.dependencyInputs(claim.dependencies())
            ));
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Codex execution failed.", e);
        }
    }

    private List<CodexDependencyInput> dependencyInputs(final List<NodeDependencyOutput> dependencies) {
        if (dependencies.isEmpty()) {
            return List.of();
        }
        return dependencies.stream()
                .map(dependency -> new CodexDependencyInput(
                        dependency.agentName(),
                        this.parseDependencyOutput(dependency)
                ))
                .toList();
    }

    private JsonNode parseDependencyOutput(final NodeDependencyOutput dependency) {
        try {
            return this.objectMapper.readTree(dependency.output().jsonValue());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Codex execution failed.", e);
        }
    }

    private JsonNode parseOutputSchema(final NodeExecutionClaim claim) {
        if (claim.outputSchema() == null || claim.outputSchema().jsonObject() == null) {
            throw new IllegalStateException("Agent output schema is invalid.");
        }
        try {
            final JsonNode schema = this.objectMapper.readTree(claim.outputSchema().jsonObject());
            if (schema == null || !schema.isObject()) {
                throw new IllegalStateException("Agent output schema is invalid.");
            }
            return schema;
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Agent output schema is invalid.", e);
        }
    }

    private String canonicalizeOutput(final String outputText) {
        if (outputText == null || outputText.isBlank()) {
            throw new IllegalStateException("Codex output was not valid JSON.");
        }
        try {
            return this.objectMapper.writeValueAsString(this.objectMapper.readTree(outputText));
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Codex output was not valid JSON.", e);
        }
    }

    private record CodexAgentInput(String workflowInput, List<CodexDependencyInput> dependencyResults) {
    }

    private record CodexDependencyInput(String agentName, JsonNode output) {
    }
}
