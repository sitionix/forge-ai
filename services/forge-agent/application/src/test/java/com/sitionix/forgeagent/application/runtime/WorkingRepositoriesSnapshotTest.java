package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkingRepositoriesSnapshotTest {
    @Mock ProjectRepositoryLinkRepository repositories;
    @Mock AgentDefinitionRepository agents;
    @Mock AgentExecutionProviderCapabilities capabilities;
    private final UUID project = UUID.randomUUID();
    private final UUID extra = UUID.randomUUID();

    @Test
    void snapshotsExplicitRepositoryOutsideTaskScope() {
        var agent = new AgentDefinition(UUID.randomUUID(), project, "api", "api", "instructions", null,
                new AgentModelSelection("codex", "model", null), Instant.EPOCH, Instant.EPOCH);
        when(agents.findByIds(any())).thenReturn(List.of(agent));
        var node = new Node(UUID.randomUUID(), agent.id(), NodeInputMode.DEPENDENCIES_ONLY, List.of(), List.of(),
                new NodePosition(0,0), NodeScopeMode.GLOBAL, NodeContextMode.FRESH_EACH_NODE_RUN, null,
                NodeType.AGENT, false, List.of(extra));
        var workflow = new Workflow(UUID.randomUUID(), project, "workflow", "workflow", List.of(node), List.of(),
                null, null, Instant.EPOCH, Instant.EPOCH);
        when(repositories.findByProjectId(project)).thenReturn(List.of(new ProjectRepositoryLink(extra, project, "remote", Instant.EPOCH)));
        var graph = new WorkflowRunSnapshotBuilder(new com.sitionix.forgeagent.application.graph.WorkflowWorkspaceValidator(repositories), agents, capabilities).build(UUID.randomUUID(), workflow, List.of());
        assertThat(graph.nodes().getFirst().resolvedWorkspaceRepositoryIds()).containsExactly(extra);
    }

    @Test
    void defaultAndAdditiveSnapshotsPreserveTaskFirstOrderAndDeduplicate() {
        final UUID taskA = UUID.randomUUID(), taskB = UUID.randomUUID();
        assertThat(snapshot(true, List.of(), List.of(taskA, taskB)).resolvedWorkspaceRepositoryIds())
                .containsExactly(taskA, taskB);
        assertThat(snapshot(true, List.of(taskB, extra, extra), List.of(taskA, taskB)).resolvedWorkspaceRepositoryIds())
                .containsExactly(taskA, taskB, extra);
        assertThat(snapshot(false, List.of(extra), List.of(taskA, taskB)).resolvedWorkspaceRepositoryIds())
                .containsExactly(extra);
        assertThat(snapshot(true, List.of(), List.of()).resolvedWorkspaceRepositoryIds()).isEmpty();
    }

    @Test
    void rejectsForeignOrRemovedRepositoryBeforeSnapshotIsCreated() {
        final var node = new Node(UUID.randomUUID(), UUID.randomUUID(), NodeInputMode.DEPENDENCIES_ONLY,
                List.of(), List.of(), new NodePosition(0,0), NodeScopeMode.GLOBAL,
                NodeContextMode.FRESH_EACH_NODE_RUN, null, NodeType.AGENT, false, List.of(extra));
        final var workflow = new Workflow(UUID.randomUUID(), project, "wf", "wf", List.of(node), List.of(),
                null, null, Instant.EPOCH, Instant.EPOCH);
        final var validator = new com.sitionix.forgeagent.application.graph.WorkflowWorkspaceValidator(repositories);
        assertThatThrownBy(() -> validator.validate(project, workflow.nodes()))
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ValidationException.class);
        assertThatThrownBy(() -> new WorkflowRunSnapshotBuilder(validator, agents, capabilities)
                .build(UUID.randomUUID(), workflow, List.of()))
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ValidationException.class);
        verifyNoInteractions(agents, capabilities);
    }

    private RunNode snapshot(boolean include, List<UUID> extras, List<UUID> taskIds) {
        var agent = new AgentDefinition(UUID.randomUUID(), project, "api", "api", "instructions", null,
                new AgentModelSelection("codex", "model", null), Instant.EPOCH, Instant.EPOCH);
        when(agents.findByIds(any())).thenReturn(List.of(agent));
        var node = new Node(UUID.randomUUID(), agent.id(), NodeInputMode.DEPENDENCIES_ONLY, List.of(), List.of(),
                new NodePosition(0,0), NodeScopeMode.GLOBAL, NodeContextMode.FRESH_EACH_NODE_RUN, null,
                NodeType.AGENT, include, extras);
        var workflow = new Workflow(UUID.randomUUID(), project, "workflow", "workflow", List.of(node), List.of(),
                null, null, Instant.EPOCH, Instant.EPOCH);
        if (!extras.isEmpty()) when(repositories.findByProjectId(project)).thenReturn(extras.stream()
                .map(id -> new ProjectRepositoryLink(id, project, "remote", Instant.EPOCH)).toList());
        return new WorkflowRunSnapshotBuilder(new com.sitionix.forgeagent.application.graph.WorkflowWorkspaceValidator(repositories), agents, capabilities)
                .build(UUID.randomUUID(), workflow, taskIds).nodes().getFirst();
    }
}
