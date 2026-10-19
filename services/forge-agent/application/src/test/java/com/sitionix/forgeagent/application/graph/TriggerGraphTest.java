package com.sitionix.forgeagent.application.graph;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TriggerGraphTest {
    private final UUID input = UUID.randomUUID(), output = UUID.randomUUID();
    private Node trigger(UUID target, NodeScopeMode scope) {
        return new Node(UUID.randomUUID(), target, NodeInputMode.DEPENDENCIES_ONLY,
                List.of(new NodePort(input,"Start","Trigger entry",0)),
                List.of(new NodePort(output,"Item","Selected item",0)), new NodePosition(0,0),
                scope, NodeContextMode.FRESH_EACH_NODE_RUN, null, NodeType.valueOf("TRIGGER"));
    }
    @Test void savesTriggerWithoutAnAgent() {
        var result = new WorkflowGraphValidator().validateAndNormalize(UUID.randomUUID(),
                List.of(trigger(null,NodeScopeMode.GLOBAL)),List.of(),input,output,List.of());
        assertThat(result.nodes()).singleElement().satisfies(n -> {
            assertThat(n.targetId()).isNull(); assertThat(n.nodeType().name()).isEqualTo("TRIGGER");
        });
    }
    @Test void rejectsAgentTargetOnTrigger() {
        assertThatThrownBy(() -> new WorkflowGraphValidator().validateAndNormalize(UUID.randomUUID(),
                List.of(trigger(UUID.randomUUID(),NodeScopeMode.GLOBAL)),List.of(),input,output,List.of()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Trigger");
    }
    @Test void rejectsPerRepositoryTrigger() {
        assertThatThrownBy(() -> new WorkflowGraphValidator().validateAndNormalize(UUID.randomUUID(),
                List.of(trigger(null,NodeScopeMode.PER_SCOPE)),List.of(),input,output,List.of()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Trigger");
    }
}
