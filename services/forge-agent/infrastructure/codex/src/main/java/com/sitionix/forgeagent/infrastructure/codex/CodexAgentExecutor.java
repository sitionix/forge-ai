package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.AgentExecutionResult;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.domain.model.NodeInputContribution;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.RunPort;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class CodexAgentExecutor implements AgentExecutor {

    private static final String PROVIDER_ID = "codex";
    private static final String PAYLOAD_SCHEMA_DEFINITION = "__forge_payload";
    private static final String PAYLOAD_SCHEMA_POINTER = "#/$defs/" + PAYLOAD_SCHEMA_DEFINITION;

    private final ObjectMapper objectMapper;
    private final CodexClient client;

    @Override
    public AgentExecutionResult execute(final NodeExecutionClaim claim) {
        if (!PROVIDER_ID.equals(claim.executionModel().providerId())) {
            throw new IllegalStateException("Agent provider is not supported.");
        }
        final JsonNode userOutputSchema = this.parseOutputSchema(claim);
        final boolean selectionRequired = claim.availableOutputs().size() > 1;
        final JsonNode effectiveOutputSchema = selectionRequired
                ? this.effectiveOutputSchema(userOutputSchema, claim.availableOutputs())
                : userOutputSchema;
        final String outputText = this.client.execute(new CodexTurnRequest(
                this.userInput(claim),
                WorkflowExecutionDeveloperInstructions.compose(claim.agentInstructions()),
                claim.executionModel().modelId(),
                claim.executionModel().effortId(),
                effectiveOutputSchema,
                claim.executionWorkspace()
        ));
        return this.parseExecutionResult(outputText, claim.availableOutputs(), selectionRequired);
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
                if (contribution.sourceRepositoryId() == null) {
                    contributionNode.putNull("sourceRepositoryId");
                } else {
                    contributionNode.put("sourceRepositoryId", contribution.sourceRepositoryId().toString());
                }
                contributionNode.set("payload", this.parsePayload(contribution.payload()));
                contributions.add(contributionNode);
            }
            if (claim.availableOutputs().size() > 1) {
                final ArrayNode availableOutputs = input.putArray("availableOutputs");
                for (final RunPort outputPort : claim.availableOutputs()) {
                    final ObjectNode outputNode = this.objectMapper.createObjectNode();
                    outputNode.put("id", outputPort.sourcePortId().toString());
                    outputNode.put("name", outputPort.name());
                    outputNode.put("description", outputPort.description());
                    availableOutputs.add(outputNode);
                }
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

    private ObjectNode effectiveOutputSchema(final JsonNode userOutputSchema,
                                             final java.util.List<RunPort> outputs) {
        final ObjectNode schema = this.objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        final ObjectNode definitions = schema.putObject("$defs");
        final ObjectNode payloadSchema = userOutputSchema.deepCopy();
        this.rewriteLocalReferences(payloadSchema, true);
        definitions.set(PAYLOAD_SCHEMA_DEFINITION, payloadSchema);
        final ObjectNode properties = schema.putObject("properties");
        properties.putObject("payload").put("$ref", PAYLOAD_SCHEMA_POINTER);
        final ObjectNode forgeSchema = properties.putObject("__forge");
        forgeSchema.put("type", "object");
        forgeSchema.put("additionalProperties", false);
        final ObjectNode forgeProperties = forgeSchema.putObject("properties");
        final ObjectNode outputPortId = forgeProperties.putObject("outputPortId");
        outputPortId.put("type", "string");
        final ArrayNode allowed = outputPortId.putArray("enum");
        outputs.forEach(output -> allowed.add(output.sourcePortId().toString()));
        forgeSchema.set("required", this.objectMapper.createArrayNode().add("outputPortId"));
        schema.set("required", this.objectMapper.createArrayNode().add("payload").add("__forge"));
        return schema;
    }

    private void rewriteLocalReferences(final JsonNode node, final boolean inheritedForgeRoot) {
        if (node.isObject()) {
            final ObjectNode object = (ObjectNode) node;
            final boolean usesForgeRoot = inheritedForgeRoot && !object.has("$id");
            final JsonNode reference = object.get("$ref");
            if (usesForgeRoot && reference != null && reference.isTextual()) {
                final String value = reference.textValue();
                if ("#".equals(value)) {
                    object.put("$ref", PAYLOAD_SCHEMA_POINTER);
                } else if (value.startsWith("#/")) {
                    object.put("$ref", PAYLOAD_SCHEMA_POINTER + value.substring(1));
                }
            }
            object.fields().forEachRemaining(entry -> this.rewriteLocalReferences(entry.getValue(), usesForgeRoot));
        } else if (node.isArray()) {
            node.forEach(child -> this.rewriteLocalReferences(child, inheritedForgeRoot));
        }
    }

    private AgentExecutionResult parseExecutionResult(final String outputText,
                                                       final java.util.List<RunPort> outputs,
                                                       final boolean selectionRequired) {
        if (outputText == null || outputText.isBlank()) {
            throw new IllegalStateException("Codex output was not valid JSON.");
        }
        try {
            final JsonNode json = this.objectMapper.readTree(outputText);
            if (!selectionRequired) {
                return new AgentExecutionResult(new NodeRunOutput(this.objectMapper.writeValueAsString(json)), null);
            }
            if (json == null || !json.isObject() || !json.has("payload")) {
                throw new IllegalStateException("Codex output did not contain the required Forge routing envelope.");
            }
            final JsonNode selectedNode = json.path("__forge").path("outputPortId");
            if (!selectedNode.isTextual() || selectedNode.asText().isBlank()) {
                throw new IllegalStateException("Codex output did not select an output port.");
            }
            final UUID selected = UUID.fromString(selectedNode.asText());
            final Set<UUID> allowed = new HashSet<>();
            outputs.forEach(output -> allowed.add(output.sourcePortId()));
            if (!allowed.contains(selected)) {
                throw new IllegalStateException("Codex output selected an unknown output port.");
            }
            return new AgentExecutionResult(
                    new NodeRunOutput(this.objectMapper.writeValueAsString(json.get("payload"))),
                    selected
            );
        } catch (final JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Codex output was not valid JSON.", e);
        }
    }
}
