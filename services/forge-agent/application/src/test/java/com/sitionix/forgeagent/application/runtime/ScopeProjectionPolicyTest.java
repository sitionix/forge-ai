package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScopeProjectionPolicyTest {

    private static final UUID NEXUS = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final ScopeProjectionPolicy policy = new ScopeProjectionPolicy();

    @Test
    void globalToGlobalProjectsExactlyOneGlobalInvocation() {
        assertThat(this.policy.project(NodeScopeMode.GLOBAL, NodeScopeMode.GLOBAL, null, List.of(NEXUS, AGENT)))
                .containsExactly((UUID) null);
    }

    @Test
    void globalToPerScopeBroadcastsInSnapshotOrder() {
        assertThat(this.policy.project(NodeScopeMode.GLOBAL, NodeScopeMode.PER_SCOPE, null, List.of(NEXUS, AGENT)))
                .containsExactly(NEXUS, AGENT);
    }

    @Test
    void perScopeToPerScopeKeepsOnlyTheSourceRepository() {
        assertThat(this.policy.project(NodeScopeMode.PER_SCOPE, NodeScopeMode.PER_SCOPE, NEXUS, List.of(NEXUS, AGENT)))
                .containsExactly(NEXUS);
        assertThat(this.policy.project(NodeScopeMode.PER_SCOPE, NodeScopeMode.PER_SCOPE, AGENT, List.of(NEXUS, AGENT)))
                .containsExactly(AGENT);
    }

    @Test
    void perScopeToGlobalGathersIntoOneGlobalInvocation() {
        assertThat(this.policy.project(NodeScopeMode.PER_SCOPE, NodeScopeMode.GLOBAL, NEXUS, List.of(NEXUS, AGENT)))
                .containsExactly((UUID) null);
    }

    @Test
    void perScopeSourceWithoutRepositoryFailsClosed() {
        assertThatThrownBy(() -> this.policy.project(NodeScopeMode.PER_SCOPE, NodeScopeMode.GLOBAL, null, List.of(NEXUS)))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("MISSING_NODE_RUN_REPOSITORY");
    }

    @Test
    void perScopeSourceOutsideWorkflowSnapshotFailsClosed() {
        assertThatThrownBy(() -> this.policy.project(
                NodeScopeMode.PER_SCOPE, NodeScopeMode.GLOBAL, AGENT, List.of(NEXUS)))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo("NODE_RUN_REPOSITORY_OUTSIDE_SNAPSHOT");
    }
}
