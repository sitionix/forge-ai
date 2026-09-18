package com.sitionix.forgeagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.SaveWorkflowRequest;
import com.sitionix.forgeagent.application.graph.WorkflowGraphValidator;
import com.sitionix.forgeagent.domain.model.Node;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NodeTypeContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ForgeAgentApiMapper mapper = new ForgeAgentApiMapper(json);
    private final WorkflowGraphValidator validator = new WorkflowGraphValidator();
    private final UUID projectId = UUID.randomUUID();

    @Test
    void omittedTypeDefaultsToAgent() throws Exception {
        Node node = node("", UUID.randomUUID());
        assertThat(json.valueToTree(node).path("nodeType").asText()).isEqualTo("AGENT");
    }

    @Test
    void manualWithoutTargetIsAcceptedAndPreservedByNormalization() throws Exception {
        Node node = node(", \"nodeType\": \"MANUAL\"", null);
        var graph = validator.validateAndNormalize(projectId, List.of(node), List.of(),
                node.inputs().getFirst().id(), node.outputs().getFirst().id(), List.of());
        assertThat(graph.nodes().getFirst().targetId()).isNull();
        assertThat(json.valueToTree(graph.nodes().getFirst()).path("nodeType").asText()).isEqualTo("MANUAL");
    }

    @Test
    void agentWithoutTargetIsRejected() throws Exception {
        Node node = node(", \"nodeType\": \"AGENT\"", null);
        assertThatThrownBy(() -> validator.validateAndNormalize(projectId, List.of(node), List.of(),
                node.inputs().getFirst().id(), node.outputs().getFirst().id(), List.of()))
                .extracting("code").isEqualTo("UNKNOWN_NODE_TARGET");
    }

    @Test
    void manualWithTargetIsRejected() throws Exception {
        Node node = node(", \"nodeType\": \"MANUAL\"", UUID.randomUUID());
        assertThatThrownBy(() -> validator.validateAndNormalize(projectId, List.of(node), List.of(),
                node.inputs().getFirst().id(), node.outputs().getFirst().id(), List.of()))
                .extracting("code").isEqualTo("INVALID_MANUAL_NODE_TARGET");
    }

    @Test
    void unknownTypeIsRejectedAsTypedValidationError() {
        assertThatThrownBy(() -> node(", \"nodeType\": \"SYSTEM\"", null))
                .extracting("code").isEqualTo("INVALID_NODE_TYPE");
    }

    private Node node(String typeField, UUID target) throws Exception {
        String payload = """
                {"name":"Manual flow","nodes":[{
                  "id":"40000000-0000-4000-8000-000000000001",
                  "targetId":%s, "scopeMode":"GLOBAL"%s,
                  "inputs":[{"id":"61000000-0000-4000-8000-000000000001","name":"In","description":"Input","order":0}],
                  "outputs":[{"id":"62000000-0000-4000-8000-000000000001","name":"Continue","description":"Continue flow","order":0}]
                }]}
                """.formatted(target == null ? "null" : "\"" + target + "\"", typeField);
        return mapper.toCommand(json.readValue(payload, SaveWorkflowRequest.class)).nodes().getFirst();
    }
}
