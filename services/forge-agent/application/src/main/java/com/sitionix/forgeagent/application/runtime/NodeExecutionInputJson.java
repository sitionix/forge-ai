package com.sitionix.forgeagent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.model.NodeInputContribution;
import com.sitionix.forgeagent.domain.model.RunPort;
import java.util.List;

/** The existing input wire format, shared by ordinary and Dialogue executions. */
public final class NodeExecutionInputJson {
    private NodeExecutionInputJson() { }
    public static ObjectNode build(final ObjectMapper json, final NodeInputEnvelope envelope, final List<RunPort> outputs) {
        try {
            final ObjectNode input = json.createObjectNode();

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
                final ObjectNode contributionNode = json.createObjectNode();
                contributionNode.put("sourceNodeRunId", contribution.sourceNodeRunId().toString());
                contributionNode.put("sourceConnectionId", contribution.sourceConnectionId().toString());
                if (contribution.sourceRepositoryId() == null) {
                    contributionNode.putNull("sourceRepositoryId");
                } else {
                    contributionNode.put("sourceRepositoryId", contribution.sourceRepositoryId().toString());
                }
                contributionNode.set("payload", json.readTree(contribution.payload().jsonValue()));
                contributions.add(contributionNode);
            }
            if (outputs.size() > 1) {
                final ArrayNode availableOutputs = input.putArray("availableOutputs");
                for (final RunPort outputPort : outputs) {
                    final ObjectNode outputNode = json.createObjectNode();
                    outputNode.put("id", outputPort.sourcePortId().toString());
                    outputNode.put("name", outputPort.name());
                    outputNode.put("description", outputPort.description());
                    availableOutputs.add(outputNode);
                }
            }
            return input;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Execution input is not valid JSON.",failure);
        }
    }
}
