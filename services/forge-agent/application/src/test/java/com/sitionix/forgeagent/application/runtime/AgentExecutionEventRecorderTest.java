package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventType;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.port.AgentExecutionEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentExecutionEventRecorderTest {

    @Mock
    private AgentExecutionEventRepository repository;

    @Test
    void appendFailureDegradesCaptureWithoutEscapingIntoExecution() {
        final AgentSessionExecutionClaim claim = claim();
        final AgentExecutionEventCandidate event = new AgentExecutionEventCandidate(
                AgentExecutionEventType.COMMAND,
                AgentExecutionEventStatus.FAILED,
                null,
                "item:command-1:completed",
                "{\"command\":\"false\",\"exitCode\":1}",
                Instant.parse("2026-09-07T09:00:00Z")
        );
        doThrow(new IllegalStateException("database unavailable")).when(this.repository).append(claim, event);

        final AgentExecutionEventRecorder recorder = new AgentExecutionEventRecorder(this.repository);

        assertThatCode(() -> recorder.record(claim, event)).doesNotThrowAnyException();
        verify(this.repository).markDegraded(claim);
    }

    @Test
    void degradationFailureAlsoDoesNotEscapeIntoExecution() {
        final AgentSessionExecutionClaim claim = claim();
        final AgentExecutionEventCandidate event = new AgentExecutionEventCandidate(
                AgentExecutionEventType.WARNING, null, null, null,
                "{\"message\":\"transient warning\"}", Instant.parse("2026-09-07T09:00:01Z"));
        doThrow(new IllegalStateException("append failed")).when(this.repository).append(claim, event);
        doThrow(new IllegalStateException("status failed")).when(this.repository).markDegraded(claim);

        final AgentExecutionEventRecorder recorder = new AgentExecutionEventRecorder(this.repository);

        assertThatCode(() -> recorder.record(claim, event)).doesNotThrowAnyException();
    }

    private static AgentSessionExecutionClaim claim() {
        return new AgentSessionExecutionClaim(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "worker-a", 7L, Instant.parse("2026-09-07T10:00:00Z"),
                "thread-1", "codex", NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, "0.153.2"
        );
    }
}
