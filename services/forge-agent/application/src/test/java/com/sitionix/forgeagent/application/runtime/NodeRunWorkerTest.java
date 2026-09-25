package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeRunWorkerTest {

    private static final UUID WORKFLOW_RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID NODE_RUN_A = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID NODE_RUN_B = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final AgentOutputSchema OUTPUT_SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}");
    private static final NodeRunExecutionModel EXECUTION_MODEL = new NodeRunExecutionModel("codex", "model-a", "medium");

    @Mock
    private NodeRunRepository nodeRunRepository;
    @Mock
    private NodeRunLifecycle lifecycle;
    @Mock
    private AgentExecutor agentExecutor;
    @Mock
    private AgentExecutionRecoveryService recoveryService;

    @Mock
    private ManualNodeRunLifecycle manualLifecycle;

    private ExecutorService executorService;
    private NodeRunWorker worker;

    @Test
    void mcpFailureDoesNotPersistRawExceptionMessage() {
        when(this.nodeRunRepository.findPendingIds()).thenReturn(List.of(NODE_RUN_A));
        when(this.lifecycle.tryStart(NODE_RUN_A)).thenReturn(Optional.of(this.claim(NODE_RUN_A)));
        when(this.agentExecutor.execute(this.claim(NODE_RUN_A)))
                .thenThrow(new InfrastructureExecutionException("MCP_EXECUTION_FAILED", "synthetic-secret-canary"));

        this.worker.poll();
        this.executorService.close();

        verify(this.lifecycle).fail(NODE_RUN_A,
                new NodeRunFailure("MCP_EXECUTION_FAILED", "MCP execution failed."));
    }

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(this.nodeRunRepository.findById(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> Optional.of(new com.sitionix.forgeagent.domain.model.NodeRun(
                        invocation.getArgument(0), WORKFLOW_RUN_ID, AGENT_ID, AGENT_ID, "Agent", "Instructions",
                        OUTPUT_SCHEMA, com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                        new com.sitionix.forgeagent.domain.model.NodePosition(0, 0), UUID.randomUUID(),
                        null, null, null, null, com.sitionix.forgeagent.domain.model.NodeRunStatus.PENDING,
                        null, null, EXECUTION_MODEL, java.time.Instant.EPOCH, null, null, null)));
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.worker = new NodeRunWorker(this.nodeRunRepository, this.lifecycle, this.agentExecutor, this.executorService, this.recoveryService, this.manualLifecycle);
    }

    @Test
    void reconcilesExpiredExecutionOnceBeforeScanningPendingNodeRuns() {
        when(this.nodeRunRepository.findPendingIds()).thenReturn(List.of());

        this.worker.poll();

        final InOrder order = inOrder(this.recoveryService, this.nodeRunRepository);
        order.verify(this.recoveryService, times(1)).reconcileExpired();
        order.verify(this.nodeRunRepository, times(1)).findPendingIds();
        order.verifyNoMoreInteractions();
    }

    @Test
    void orphanReturnedByHostilePendingScanIsNotSubmittedWhileUnrelatedNodeRunsOnce() {
        when(this.nodeRunRepository.findPendingIds()).thenReturn(List.of(NODE_RUN_A, NODE_RUN_B));
        when(this.lifecycle.tryStart(NODE_RUN_A)).thenReturn(Optional.empty());
        when(this.lifecycle.tryStart(NODE_RUN_B)).thenReturn(Optional.of(this.claim(NODE_RUN_B)));
        when(this.agentExecutor.execute(this.claim(NODE_RUN_B)))
                .thenReturn(new AgentExecutionResult(new NodeRunOutput("{\"ok\":true}"), null));

        this.worker.poll();
        this.executorService.close();

        verify(this.lifecycle, times(1)).tryStart(NODE_RUN_A);
        verify(this.lifecycle, times(1)).tryStart(NODE_RUN_B);
        verify(this.agentExecutor, never()).execute(this.claim(NODE_RUN_A));
        verify(this.agentExecutor, times(1)).execute(this.claim(NODE_RUN_B));
        verify(this.lifecycle, never()).succeed(
                org.mockito.ArgumentMatchers.eq(NODE_RUN_A),
                org.mockito.ArgumentMatchers.any(AgentExecutionResult.class)
        );
        verify(this.lifecycle, times(1)).succeed(
                NODE_RUN_B,
                new AgentExecutionResult(new NodeRunOutput("{\"ok\":true}"), null)
        );
    }

    @AfterEach
    void tearDown() {
        this.executorService.shutdownNow();
    }

    @Test
    void twoIndependentEligibleNodesAreSubmittedWithoutWaitingForEachOtherToComplete() throws Exception {
        final CountDownLatch enteredExecutions = new CountDownLatch(2);
        final CountDownLatch releaseExecutions = new CountDownLatch(1);
        when(this.nodeRunRepository.findPendingIds()).thenReturn(List.of(NODE_RUN_A, NODE_RUN_B));
        when(this.lifecycle.tryStart(NODE_RUN_A)).thenReturn(Optional.of(this.claim(NODE_RUN_A)));
        when(this.lifecycle.tryStart(NODE_RUN_B)).thenReturn(Optional.of(this.claim(NODE_RUN_B)));
        when(this.agentExecutor.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            enteredExecutions.countDown();
            assertThat(releaseExecutions.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            return new AgentExecutionResult(new NodeRunOutput("{\"ok\":true}"), null);
        });

        this.worker.poll();

        assertThat(enteredExecutions.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        releaseExecutions.countDown();
        this.executorService.close();
        verify(this.lifecycle).succeed(NODE_RUN_A, new AgentExecutionResult(new NodeRunOutput("{\"ok\":true}"), null));
        verify(this.lifecycle).succeed(NODE_RUN_B, new AgentExecutionResult(new NodeRunOutput("{\"ok\":true}"), null));
    }

    @Test
    void repeatedPollDoesNotExecuteAlreadyRunningNodeTwice() throws Exception {
        final CountDownLatch enteredExecution = new CountDownLatch(1);
        final CountDownLatch releaseExecution = new CountDownLatch(1);
        when(this.nodeRunRepository.findPendingIds()).thenReturn(List.of(NODE_RUN_A));
        when(this.lifecycle.tryStart(NODE_RUN_A)).thenReturn(Optional.of(this.claim(NODE_RUN_A)), Optional.empty());
        when(this.agentExecutor.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            enteredExecution.countDown();
            assertThat(releaseExecution.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            return new AgentExecutionResult(new NodeRunOutput("{\"ok\":true}"), null);
        });

        this.worker.poll();
        assertThat(enteredExecution.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        this.worker.poll();
        releaseExecution.countDown();
        this.executorService.close();

        verify(this.agentExecutor).execute(this.claim(NODE_RUN_A));
        verify(this.lifecycle).succeed(NODE_RUN_A, new AgentExecutionResult(new NodeRunOutput("{\"ok\":true}"), null));
    }

    @Test
    void nonEligibleNodeDoesNotCallExecutor() {
        when(this.nodeRunRepository.findPendingIds()).thenReturn(List.of(NODE_RUN_A));
        when(this.lifecycle.tryStart(NODE_RUN_A)).thenReturn(Optional.empty());

        this.worker.poll();

        verify(this.agentExecutor, never()).execute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void executorRuntimeExceptionPersistsMeaningfulFailureMessage() {
        when(this.nodeRunRepository.findPendingIds()).thenReturn(List.of(NODE_RUN_A));
        when(this.lifecycle.tryStart(NODE_RUN_A)).thenReturn(Optional.of(this.claim(NODE_RUN_A)));
        when(this.agentExecutor.execute(this.claim(NODE_RUN_A))).thenThrow(new IllegalStateException("Codex output was not valid JSON."));

        this.worker.poll();
        this.executorService.close();

        verify(this.lifecycle).fail(
                NODE_RUN_A,
                new NodeRunFailure(NodeRunWorker.AGENT_EXECUTOR_FAILED, "Codex output was not valid JSON.")
        );
        verify(this.lifecycle, never()).succeed(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(AgentExecutionResult.class));
    }

    @Test
    void executorRuntimeExceptionWithoutMessagePersistsGenericFailureMessage() {
        when(this.nodeRunRepository.findPendingIds()).thenReturn(List.of(NODE_RUN_A));
        when(this.lifecycle.tryStart(NODE_RUN_A)).thenReturn(Optional.of(this.claim(NODE_RUN_A)));
        when(this.agentExecutor.execute(this.claim(NODE_RUN_A))).thenThrow(new RuntimeException(" "));

        this.worker.poll();
        this.executorService.close();

        verify(this.lifecycle).fail(
                NODE_RUN_A,
                new NodeRunFailure(NodeRunWorker.AGENT_EXECUTOR_FAILED, "Agent execution failed.")
        );
        verify(this.lifecycle, never()).succeed(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(AgentExecutionResult.class));
    }

    @Test
    void executorNullOutputFailsNodeRun() {
        when(this.nodeRunRepository.findPendingIds()).thenReturn(List.of(NODE_RUN_A));
        when(this.lifecycle.tryStart(NODE_RUN_A)).thenReturn(Optional.of(this.claim(NODE_RUN_A)));
        when(this.agentExecutor.execute(this.claim(NODE_RUN_A))).thenReturn(null);

        this.worker.poll();
        this.executorService.close();

        verify(this.lifecycle).fail(
                NODE_RUN_A,
                new NodeRunFailure(NodeRunWorker.AGENT_EXECUTOR_INVALID_OUTPUT, "Agent execution returned no output.")
        );
        verify(this.lifecycle, never()).succeed(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(AgentExecutionResult.class));
    }

    private NodeExecutionClaim claim(final UUID nodeRunId) {
        return new NodeExecutionClaim(
                WORKFLOW_RUN_ID,
                nodeRunId,
                AGENT_ID,
                "Review auth changes.",
                "Agent",
                "Instructions",
                OUTPUT_SCHEMA,
                EXECUTION_MODEL,
                new NodeInputEnvelope("Review auth changes.", null, List.of()),
                List.of(),
                new ExecutionWorkspace(java.nio.file.Path.of("/forge/project"), List.of())
        );
    }
}
