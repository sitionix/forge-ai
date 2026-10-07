package com.sitionix.forgeagent.application.graph;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DialogueNodePolicyTest {
    @Test void dialogueWithoutAcceptDispositionCannotRun() {
        UUID project = UUID.randomUUID(), agent = UUID.randomUUID();
        NodePort input = new NodePort(UUID.randomUUID(), "Input", "Research context", 0);
        NodePort output = new NodePort(UUID.randomUUID(), "Continue", "Accepted result", 0);
        Node node = new Node(UUID.randomUUID(), agent, NodeInputMode.TASK_AND_DEPENDENCIES,
                List.of(input), List.of(output), new NodePosition(0, 0), NodeScopeMode.GLOBAL,
                NodeContextMode.FRESH_EACH_NODE_RUN, null, NodeType.valueOf("DIALOGUE"));
        AgentDefinition definition = new AgentDefinition(agent, project, "Groomer", "groomer", "Clarify requirements",
                AgentOutputSchema.ofCanonicalJsonObject("{}"), null, Instant.EPOCH, Instant.EPOCH);
        assertThatThrownBy(() -> new WorkflowGraphValidator().validateAndNormalize(project, List.of(node), List.of(),
                input.id(), output.id(), List.of(definition)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Dialogue");
    }

    @Test void dialogueRequiresGlobalScope() {
        assertThatThrownBy(() -> new Node(UUID.randomUUID(), UUID.randomUUID(), NodeInputMode.DEPENDENCIES_ONLY,
                List.of(), List.of(), new NodePosition(0,0), NodeScopeMode.PER_SCOPE,
                null, null, NodeType.DIALOGUE)).isInstanceOf(ValidationException.class);
    }

    @Test void duplicateDispositionsAreRejected() {
        var port = new NodePort(UUID.randomUUID(), "Accept", "Result", 0,
                com.sitionix.forgeagent.domain.model.dialogue.DialogueOutputDisposition.ACCEPT);
        var node = new Node(UUID.randomUUID(), UUID.randomUUID(), NodeInputMode.DEPENDENCIES_ONLY,
                List.of(), List.of(port, port), new NodePosition(0,0), NodeScopeMode.GLOBAL,
                null, null, NodeType.DIALOGUE);
        assertThatThrownBy(() -> DialogueNodePolicy.validate(node)).isInstanceOf(ValidationException.class);
    }

    @Test void ordinaryNodesCannotUseDialogueContext() {
        assertThatThrownBy(() -> new Node(UUID.randomUUID(), UUID.randomUUID(), NodeInputMode.DEPENDENCIES_ONLY,
                List.of(), List.of(), new NodePosition(0,0), NodeScopeMode.GLOBAL,
                NodeContextMode.DIALOGUE_WITHIN_NODE_RUN, null, NodeType.AGENT))
                .isInstanceOf(ValidationException.class);
    }

    @Test void waitingForDialogueKeepsWorkflowActive() {
        assertThat(NodeRunStatus.valueOf("WAITING_FOR_DIALOGUE").active()).isTrue();
    }
}
