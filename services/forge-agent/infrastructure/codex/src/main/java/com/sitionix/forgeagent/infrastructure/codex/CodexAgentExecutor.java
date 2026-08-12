package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.AgentExecutionException;
import com.sitionix.forgeagent.application.runtime.AgentExecutionPromptBuilder;
import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public final class CodexAgentExecutor implements AgentExecutor {

    private final ObjectMapper objectMapper;
    private final AgentExecutionPromptBuilder promptBuilder;
    private final CodexTurnClient turnClient;

    @Override
    public NodeRunOutput execute(final NodeExecutionClaim claim) {
        if (claim.executionModel() == null || !CodexProtocol.PROVIDER_ID.equals(claim.executionModel().providerId())) {
            throw new AgentExecutionException("UNSUPPORTED_AGENT_PROVIDER", "Agent provider is not supported.");
        }
        final String prompt = this.promptBuilder.build(claim);
        final JsonNode outputSchema = this.parseOutputSchema(claim);
        final CodexTurnResult result = this.turnClient.execute(new CodexTurnRequest(
                prompt,
                claim.executionModel().modelId(),
                claim.executionModel().effortId(),
                outputSchema
        ));
        log.debug("Codex agent execution completed workflowRunId={} nodeRunId={} threadId={} turnId={} modelId={}",
                claim.workflowRunId(),
                claim.nodeRunId(),
                result.threadId(),
                result.turnId(),
                claim.executionModel().modelId());
        return new NodeRunOutput(this.canonicalizeOutput(result.outputText()));
    }

    private JsonNode parseOutputSchema(final NodeExecutionClaim claim) {
        if (claim.outputSchema() == null || claim.outputSchema().jsonObject() == null) {
            throw new AgentExecutionException("INVALID_AGENT_OUTPUT_SCHEMA", "Agent output schema is invalid.");
        }
        try {
            final JsonNode schema = this.objectMapper.readTree(claim.outputSchema().jsonObject());
            if (schema == null || !schema.isObject()) {
                throw new AgentExecutionException("INVALID_AGENT_OUTPUT_SCHEMA", "Agent output schema is invalid.");
            }
            return schema;
        } catch (final JsonProcessingException e) {
            throw new AgentExecutionException("INVALID_AGENT_OUTPUT_SCHEMA", "Agent output schema is invalid.", e);
        }
    }

    private String canonicalizeOutput(final String outputText) {
        if (outputText == null || outputText.isBlank()) {
            throw new AgentExecutionException("CODEX_OUTPUT_INVALID", "Codex output was not valid JSON.");
        }
        try {
            return this.objectMapper.writeValueAsString(this.objectMapper.readTree(outputText));
        } catch (final JsonProcessingException e) {
            throw new AgentExecutionException("CODEX_OUTPUT_INVALID", "Codex output was not valid JSON.", e);
        }
    }
}
