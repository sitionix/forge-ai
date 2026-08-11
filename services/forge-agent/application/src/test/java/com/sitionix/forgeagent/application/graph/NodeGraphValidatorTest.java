package com.sitionix.forgeagent.application.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodePosition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeGraphValidatorTest {

    private static final AgentOutputSchema OUTPUT_SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("{}");

    private final NodeGraphValidator validator = new NodeGraphValidator();
    private final UUID projectId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID otherProjectId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID nodeA = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID nodeB = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final UUID nodeC = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private final UUID agentA = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private final UUID agentB = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

    @Test
    void normalizesDuplicateDependencies() {
        final List<Node> normalized = this.validator.validateAndNormalize(
                this.projectId,
                List.of(new Node(this.nodeB, this.agentB, List.of(this.nodeA, this.nodeA), new NodePosition(1.0, 2.0)),
                        new Node(this.nodeA, this.agentA, List.of(), new NodePosition(3.0, 4.0))),
                List.of(this.agent(this.agentA, this.projectId), this.agent(this.agentB, this.projectId))
        );

        assertThat(normalized.getFirst().dependsOnNodeIds()).containsExactly(this.nodeA);
    }

    @Test
    void rejectsDuplicateNodeId() {
        assertThatThrownBy(() -> this.validator.validateAndNormalize(
                this.projectId,
                List.of(new Node(this.nodeA, this.agentA, List.of(), new NodePosition(0, 0)),
                        new Node(this.nodeA, this.agentB, List.of(), new NodePosition(0, 0))),
                List.of(this.agent(this.agentA, this.projectId), this.agent(this.agentB, this.projectId))
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("DUPLICATE_NODE_ID");
    }

    @Test
    void rejectsUnknownTarget() {
        assertThatThrownBy(() -> this.validator.validateAndNormalize(
                this.projectId,
                List.of(new Node(this.nodeA, this.agentA, List.of(), new NodePosition(0, 0))),
                List.of()
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("UNKNOWN_NODE_TARGET");
    }

    @Test
    void rejectsCrossProjectTarget() {
        assertThatThrownBy(() -> this.validator.validateAndNormalize(
                this.projectId,
                List.of(new Node(this.nodeA, this.agentA, List.of(), new NodePosition(0, 0))),
                List.of(this.agent(this.agentA, this.otherProjectId))
        ))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("CROSS_PROJECT_NODE_TARGET");
    }

    @Test
    void rejectsUnknownDependency() {
        assertThatThrownBy(() -> this.validator.validateAndNormalize(
                this.projectId,
                List.of(new Node(this.nodeA, this.agentA, List.of(this.nodeB), new NodePosition(0, 0))),
                List.of(this.agent(this.agentA, this.projectId))
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("UNKNOWN_NODE_DEPENDENCY");
    }

    @Test
    void rejectsSelfDependency() {
        assertThatThrownBy(() -> this.validator.validateAndNormalize(
                this.projectId,
                List.of(new Node(this.nodeA, this.agentA, List.of(this.nodeA), new NodePosition(0, 0))),
                List.of(this.agent(this.agentA, this.projectId))
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("SELF_NODE_DEPENDENCY");
    }

    @Test
    void rejectsDirectAndIndirectCycles() {
        assertThatThrownBy(() -> this.validator.validateAndNormalize(
                this.projectId,
                List.of(new Node(this.nodeA, this.agentA, List.of(this.nodeB), new NodePosition(0, 0)),
                        new Node(this.nodeB, this.agentB, List.of(this.nodeA), new NodePosition(0, 0))),
                List.of(this.agent(this.agentA, this.projectId), this.agent(this.agentB, this.projectId))
        ))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_GRAPH_CYCLE");

        assertThatThrownBy(() -> this.validator.validateAndNormalize(
                this.projectId,
                List.of(new Node(this.nodeA, this.agentA, List.of(this.nodeC), new NodePosition(0, 0)),
                        new Node(this.nodeB, this.agentB, List.of(this.nodeA), new NodePosition(0, 0)),
                        new Node(this.nodeC, this.agentA, List.of(this.nodeB), new NodePosition(0, 0))),
                List.of(this.agent(this.agentA, this.projectId), this.agent(this.agentB, this.projectId))
        ))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_GRAPH_CYCLE");
    }

    private AgentDefinition agent(final UUID id, final UUID projectId) {
        return new AgentDefinition(id, projectId, "Agent", "agent", "Instructions", OUTPUT_SCHEMA, null, Instant.EPOCH, Instant.EPOCH);
    }
}
