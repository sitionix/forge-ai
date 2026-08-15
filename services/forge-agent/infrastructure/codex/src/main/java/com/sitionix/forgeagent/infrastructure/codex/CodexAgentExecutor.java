package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.domain.model.NodeInputContribution;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
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
            final ObjectNode input = this.objectMapper.createObjectNode();
            final NodeInputEnvelope envelope = claim.inputEnvelope();
            if (envelope.originalTask() != null && !envelope.originalTask().isBlank()) {
                input.put("task", envelope.originalTask());
            }
            if (envelope.entryInputPort() != null) {
                final ObjectNode entryInput = input.putObject("entryInput");
                entryInput.put("id", envelope.entryInputPort().sourcePortId().toString());
                entryInput.put("name", envelope.entryInputPort().name());
                entryInput.put("description", envelope.entryInputPort().description());
            }
            final ArrayNode contributions = input.putArray("contributions");
            for (final NodeInputContribution contribution : envelope.contributions()) {
                final ObjectNode contributionNode = this.objectMapper.createObjectNode();
                contributionNode.put("sourceNodeRunId", contribution.sourceNodeRunId().toString());
                contributionNode.put("sourceConnectionId", contribution.sourceConnectionId().toString());
                contributionNode.set("payload", this.parsePayload(contribution.payload()));
                contributions.add(contributionNode);
            }
            return this.objectMapper.writeValueAsString(input);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Codex execution failed.", e);
        }
    }

    private JsonNode parsePayload(final NodeRunOutput output) {
        try {
            return this.objectMapper.readTree(output.jsonValue());
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
}
