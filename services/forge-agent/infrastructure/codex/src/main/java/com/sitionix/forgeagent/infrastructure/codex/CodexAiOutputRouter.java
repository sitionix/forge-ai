package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.runtime.AiOutputRouter;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.RunPort;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CodexAiOutputRouter implements AiOutputRouter {

    private final ObjectMapper objectMapper;
    private final CodexClient client;
    private final CodexRuntimeWorkspace runtimeWorkspace;

    @Override
    public UUID selectOutput(final NodeRunOutput output, final List<RunPort> outputs, final NodeRunExecutionModel executionModel) {
        final String response = this.client.execute(new CodexTurnRequest(
                this.userInput(output, outputs),
                "Select exactly one workflow output port. Respond only with JSON matching the provided schema.",
                executionModel.modelId(),
                executionModel.effortId(),
                this.outputSchema(),
                this.runtimeWorkspace.routingWorkspace()
        ));
        return this.parseSelectedOutputPortId(response);
    }

    private String userInput(final NodeRunOutput output, final List<RunPort> outputs) {
        try {
            final ObjectNode input = this.objectMapper.createObjectNode();
            input.set("businessOutput", this.objectMapper.readTree(output.jsonValue()));
            final ArrayNode availableOutputs = input.putArray("availableOutputs");
            for (final RunPort outputPort : outputs) {
                final ObjectNode node = this.objectMapper.createObjectNode();
                node.put("id", outputPort.sourcePortId().toString());
                node.put("name", outputPort.name());
                node.put("description", outputPort.description());
                availableOutputs.add(node);
            }
            return this.objectMapper.writeValueAsString(input);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("AI output routing request could not be serialized.", e);
        }
    }

    private JsonNode outputSchema() {
        final ObjectNode schema = this.objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        final ObjectNode selectedOutputPortId = this.objectMapper.createObjectNode();
        selectedOutputPortId.put("type", "string");
        selectedOutputPortId.put("format", "uuid");
        final ObjectNode propertiesNode = this.objectMapper.createObjectNode();
        propertiesNode.set("selectedOutputPortId", selectedOutputPortId);
        schema.set("properties", propertiesNode);
        schema.set("required", this.objectMapper.createArrayNode().add("selectedOutputPortId"));
        return schema;
    }

    private UUID parseSelectedOutputPortId(final String response) {
        try {
            final JsonNode json = this.objectMapper.readTree(response);
            final JsonNode selected = json.path("selectedOutputPortId");
            if (!selected.isTextual() || selected.asText().isBlank()) {
                throw new IllegalArgumentException("AI output routing response did not contain selectedOutputPortId.");
            }
            return UUID.fromString(selected.asText());
        } catch (final IllegalArgumentException | JsonProcessingException e) {
            throw new IllegalStateException("AI output routing response was invalid.", e);
        }
    }
}
