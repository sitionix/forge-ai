package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ExecutionFrameRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReentryFrameTransitionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID NODE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID INPUT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID FRAME_A_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID FRAME_B_ID = UUID.fromString("40000000-0000-4000-8000-000000000002");
    private static final UUID FRAME_C_ID = UUID.fromString("40000000-0000-4000-8000-000000000003");
    private static final UUID REPOSITORY_A = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID REPOSITORY_B = UUID.fromString("50000000-0000-4000-8000-000000000002");

    @Mock
    private NodeRunRepository nodeRunRepository;
    @Mock
    private ExecutionFrameRepository frameRepository;
    private ReentryFrameTransitionPolicy policy;

    @BeforeEach
    void setUp() {
        this.policy = new ReentryFrameTransitionPolicy(
                this.nodeRunRepository, this.frameRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void originalActivationFrameIsNeverReusedAsItsOwnChild() {
        final ExecutionFrame frameA = this.frame(FRAME_A_ID, null);
        final ExecutionFrame frameB = this.frame(FRAME_B_ID, FRAME_A_ID);
        final NodeRun original = this.nodeRun(FRAME_A_ID, null, REPOSITORY_A);
        when(this.nodeRunRepository.findByWorkflowRunIdAndExecutionFrameId(RUN_ID, FRAME_A_ID))
                .thenReturn(List.of(original));
        when(this.nodeRunRepository.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(original));
        when(this.frameRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(frameB);

        assertThat(this.policy.frameForActivation(
                this.workflowRun(), frameA, this.targetNode(), INPUT_ID, REPOSITORY_A)).isEqualTo(frameB);
    }

    @Test
    void allRepositoriesInFirstActivationWaveRemainInParentFrame() {
        final ExecutionFrame frameA = this.frame(FRAME_A_ID, null);
        final NodeRun activatedA = this.nodeRun(FRAME_A_ID, FRAME_A_ID, REPOSITORY_A);
        when(this.nodeRunRepository.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(activatedA));
        when(this.nodeRunRepository.findByWorkflowRunIdAndExecutionFrameId(RUN_ID, FRAME_A_ID))
                .thenReturn(List.of(activatedA));

        assertThat(this.policy.frameForActivation(
                this.workflowRun(), frameA, this.targetNode(), INPUT_ID, REPOSITORY_B)).isEqualTo(frameA);
    }

    @Test
    void allRepositoriesInSameReentryWaveReuseOneChildFrame() {
        final ExecutionFrame frameA = this.frame(FRAME_A_ID, null);
        final ExecutionFrame frameB = this.frame(FRAME_B_ID, FRAME_A_ID);
        final NodeRun originalA = this.nodeRun(FRAME_A_ID, FRAME_A_ID, REPOSITORY_A);
        final NodeRun reenteredA = this.nodeRun(FRAME_B_ID, FRAME_A_ID, REPOSITORY_A);
        when(this.nodeRunRepository.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(originalA, reenteredA));
        when(this.frameRepository.findById(FRAME_B_ID)).thenReturn(Optional.of(frameB));

        assertThat(this.policy.frameForActivation(
                this.workflowRun(), frameA, this.targetNode(), INPUT_ID, REPOSITORY_B)).isEqualTo(frameB);
    }

    @Test
    void nextReentryGenerationCreatesNextChildFrame() {
        final ExecutionFrame frameB = this.frame(FRAME_B_ID, FRAME_A_ID);
        final ExecutionFrame frameC = this.frame(FRAME_C_ID, FRAME_B_ID);
        final NodeRun generationTwo = this.nodeRun(FRAME_B_ID, null, REPOSITORY_A);
        when(this.nodeRunRepository.findByWorkflowRunIdAndExecutionFrameId(RUN_ID, FRAME_B_ID))
                .thenReturn(List.of(generationTwo));
        when(this.nodeRunRepository.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(generationTwo));
        when(this.frameRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(frameC);

        assertThat(this.policy.frameForActivation(
                this.workflowRun(), frameB, this.targetNode(), INPUT_ID, REPOSITORY_A)).isEqualTo(frameC);
    }

    private ExecutionFrame frame(final UUID id, final UUID parentId) {
        return new ExecutionFrame(id, RUN_ID, parentId, NOW);
    }

    private NodeRun nodeRun(final UUID executionFrameId, final UUID activationFrameId, final UUID repositoryId) {
        return new NodeRun(UUID.randomUUID(), RUN_ID, NODE_ID, UUID.randomUUID(), "Implementer", "Implement.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"), NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(0, 0), executionFrameId, INPUT_ID, activationFrameId, null, null,
                NodeRunStatus.PENDING, null, null, new NodeRunExecutionModel("codex", "model", null),
                NOW, null, null, repositoryId);
    }

    private RunNode targetNode() {
        return new RunNode(RUN_ID, NODE_ID, UUID.randomUUID(), "Implementer", "Implement.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                new NodeRunExecutionModel("codex", "model", null), NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(0, 0), NodeScopeMode.PER_SCOPE);
    }

    private WorkflowRun workflowRun() {
        return new WorkflowRun(
                RUN_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "Workflow",
                "Input",
                WorkflowRunStatus.RUNNING,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                NOW,
                NOW,
                null,
                List.of(REPOSITORY_A, REPOSITORY_B)
        );
    }
}
