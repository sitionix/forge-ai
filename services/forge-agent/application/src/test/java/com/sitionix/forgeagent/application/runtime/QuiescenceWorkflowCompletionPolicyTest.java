package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.domain.port.InputActivationResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuiescenceWorkflowCompletionPolicyTest {

    private static final UUID RUN_ID = UUID.fromString("52000000-0000-4000-8000-000000000001");
    private static final UUID A_ID = UUID.fromString("52000000-0000-4000-8000-000000000002");
    private static final UUID B_ID = UUID.fromString("52000000-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");

    private final NodeRunRepository nodeRuns = mock(NodeRunRepository.class);
    private final WorkflowRunGraphRepository graphs = mock(WorkflowRunGraphRepository.class);
    private final ExecutionFrameRepository frames = mock(ExecutionFrameRepository.class);
    private final InputActivationResolutionRepository activations = mock(InputActivationResolutionRepository.class);
    private final InputParticipationResolver participation = mock(InputParticipationResolver.class);
    private final WorkflowCompletionRuleRegistry rules = mock(WorkflowCompletionRuleRegistry.class);
    private final QuiescenceWorkflowCompletionPolicy policy = new QuiescenceWorkflowCompletionPolicy(
            this.nodeRuns, this.graphs, this.frames, this.activations, this.participation, this.rules,
            new ScopeProjectionPolicy());

    @Test
    void historicalFailedAttemptIsExcludedAfterRetryChildExists() {
        final NodeRun failed = node(A_ID, NodeRunStatus.FAILED, null);
        final NodeRun retry = node(B_ID, NodeRunStatus.RUNNING, A_ID);
        final WorkflowRun run = workflowRun();
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(failed, retry));
        when(this.graphs.findByWorkflowRunId(RUN_ID)).thenReturn(emptyGraph());
        when(this.frames.findByWorkflowRunId(RUN_ID)).thenReturn(List.of());
        when(this.rules.evaluate(argThat(context -> context.nodeRuns().equals(List.of(retry)))))
                .thenReturn(new RunningWorkflowDecision());

        final WorkflowCompletionDecision decision = this.policy.evaluate(run);

        assertThat(decision).isInstanceOf(RunningWorkflowDecision.class);
        verify(this.rules).evaluate(argThat(context -> context.nodeRuns().equals(List.of(retry))));
    }

    @Test
    void currentFailedRetryLeafRemainsVisibleToCompletionRules() {
        final NodeRun failed = node(A_ID, NodeRunStatus.FAILED, null);
        final NodeRun retryFailure = node(B_ID, NodeRunStatus.FAILED, A_ID);
        final WorkflowRun run = workflowRun();
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(failed, retryFailure));
        when(this.graphs.findByWorkflowRunId(RUN_ID)).thenReturn(emptyGraph());
        when(this.frames.findByWorkflowRunId(RUN_ID)).thenReturn(List.of());
        when(this.rules.evaluate(argThat(context -> context.nodeRuns().equals(List.of(retryFailure)))))
                .thenReturn(new FailedWorkflowDecision());

        final WorkflowCompletionDecision decision = this.policy.evaluate(run);

        assertThat(decision).isInstanceOf(FailedWorkflowDecision.class);
    }

    private WorkflowRun workflowRun() {
        return new WorkflowRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), null, "Workflow", "input",
                WorkflowRunStatus.RUNNING, List.of(), List.of(), List.of(), null, null, null,
                NOW.minusSeconds(30), NOW.minusSeconds(20), null, List.of());
    }

    private WorkflowRunGraph emptyGraph() {
        return new WorkflowRunGraph(RUN_ID, null, null, List.of(), List.of(), List.of());
    }

    private NodeRun node(final UUID id, final NodeRunStatus status, final UUID retryOf) {
        return new NodeRun(id, RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Agent", "Instructions",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"), NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(0, 0), UUID.randomUUID(), null, null, null, null, status, null,
                status == NodeRunStatus.FAILED ? new NodeRunFailure("FAIL", "failed") : null,
                new NodeRunExecutionModel("codex", "gpt-5", null), NOW, NOW, null, null,
                NodeContextMode.FRESH_EACH_NODE_RUN, 1, retryOf);
    }
}
