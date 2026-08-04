package com.sitionix.forgeagent.application.graph;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentDependency;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DependencyGraphValidatorTest {

    private final DependencyGraphValidator validator = new DependencyGraphValidator();

    private final UUID projectId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID otherProjectId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID agentA = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID agentB = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final UUID agentC = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @Test
    void rejectsSelfDependency() {
        assertThatThrownBy(() -> this.validator.validate(this.projectId, this.agents(), List.of(new AgentDependency(this.agentA, this.agentA))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("An agent cannot depend on itself.");
    }

    @Test
    void rejectsUnknownDependency() {
        final UUID unknown = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

        assertThatThrownBy(() -> this.validator.validate(this.projectId, this.agents(), List.of(new AgentDependency(this.agentA, unknown))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Dependencies must reference existing agents.");
    }

    @Test
    void rejectsCrossProjectAgentSet() {
        assertThatThrownBy(() -> this.validator.validate(
                this.projectId,
                List.of(this.agent(this.agentA, this.projectId), this.agent(this.agentB, this.otherProjectId)),
                List.of(new AgentDependency(this.agentA, this.agentB))
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Dependencies must belong to the same project.");
    }

    @Test
    void rejectsDirectCycle() {
        assertThatThrownBy(() -> this.validator.validate(this.projectId, this.agents(), List.of(
                new AgentDependency(this.agentA, this.agentB),
                new AgentDependency(this.agentB, this.agentA)
        )))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Dependency graph contains a cycle.");
    }

    @Test
    void rejectsIndirectCycle() {
        assertThatThrownBy(() -> this.validator.validate(this.projectId, this.agents(), List.of(
                new AgentDependency(this.agentA, this.agentB),
                new AgentDependency(this.agentB, this.agentC),
                new AgentDependency(this.agentC, this.agentA)
        )))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Dependency graph contains a cycle.");
    }

    @Test
    void acceptsValidDag() {
        assertThatCode(() -> this.validator.validate(this.projectId, this.agents(), List.of(
                new AgentDependency(this.agentB, this.agentA),
                new AgentDependency(this.agentC, this.agentB)
        ))).doesNotThrowAnyException();
    }

    private List<AgentDefinition> agents() {
        return List.of(this.agent(this.agentA, this.projectId), this.agent(this.agentB, this.projectId), this.agent(this.agentC, this.projectId));
    }

    private AgentDefinition agent(final UUID id, final UUID projectId) {
        return new AgentDefinition(
                id,
                projectId,
                "Agent " + id,
                "agent-" + id,
                "Instructions",
                AgentOutputSchema.ofCanonicalJsonObject("{}"),
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:00:00Z")
        );
    }
}
