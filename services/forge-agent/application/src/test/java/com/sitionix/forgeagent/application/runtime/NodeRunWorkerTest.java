package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
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

    private ExecutorService executorService;
    private NodeRunWorker worker;

    @BeforeEach
    void setUp() {
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.worker = new NodeRunWorker(this.nodeRunRepository, this.lifecycle, this.agentExecutor, this.executorService);
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
            return new NodeRunOutput("{\"ok\":true}");
        });

        this.worker.poll();

        assertThat(enteredExecutions.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        releaseExecutions.countDown();
        this.executorService.close();
        verify(this.lifecycle).succeed(NODE_RUN_A, new NodeRunOutput("{\"ok\":true}"));
        verify(this.lifecycle).succeed(NODE_RUN_B, new NodeRunOutput("{\"ok\":true}"));
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
            return new NodeRunOutput("{\"ok\":true}");
        });

        this.worker.poll();
        assertThat(enteredExecution.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        this.worker.poll();
        releaseExecution.countDown();
        this.executorService.close();

        verify(this.agentExecutor).execute(this.claim(NODE_RUN_A));
        verify(this.lifecycle).succeed(NODE_RUN_A, new NodeRunOutput("{\"ok\":true}"));
    }

    @Test
    void nonEligibleNodeDoesNotCallExecutor() {
        when(this.nodeRunRepository.findPendingIds()).thenReturn(List.of(NODE_RUN_A));
        when(this.lifecycle.tryStart(NODE_RUN_A)).thenReturn(Optional.empty());

        this.worker.poll();

        verify(this.agentExecutor, never()).execute(org.mockito.ArgumentMatchers.any());
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
                List.of()
        );
    }
}
