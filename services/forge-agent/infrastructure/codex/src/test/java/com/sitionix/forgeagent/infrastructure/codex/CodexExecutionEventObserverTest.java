package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodexExecutionEventObserverTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void buffersBeforeTurnIdentityThenFlushesInObservedOrderAfterSemanticStart() throws Exception {
        final RecordingCallbacks callbacks = new RecordingCallbacks();
        final CodexExecutionEventObserver observer = new CodexExecutionEventObserver(
                new CodexAgentExecutionEventMapper(this.json), callbacks);
        observer.bindThread("thread-1");
        observer.observe("item/started", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","item":{"id":"cmd-1","type":"commandExecution","command":"pwd"}}
                """));
        observer.observe("item/completed", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","item":{"id":"cmd-1","type":"commandExecution","command":"pwd","status":"completed","exitCode":0}}
                """));
        assertThat(callbacks.events).isEmpty();

        observer.activate("turn-1");

        assertThat(callbacks.events).extracting(AgentExecutionEventCandidate::type)
                .containsExactly(AgentExecutionEventType.TURN, AgentExecutionEventType.COMMAND,
                        AgentExecutionEventType.COMMAND);
        assertThat(callbacks.events).extracting(AgentExecutionEventCandidate::status)
                .containsExactly(AgentExecutionEventStatus.STARTED, AgentExecutionEventStatus.STARTED,
                        AgentExecutionEventStatus.SUCCEEDED);
    }

    @Test
    void ignoresOtherCorrelationAndCompletesExactlyOnce() throws Exception {
        final RecordingCallbacks callbacks = new RecordingCallbacks();
        final CodexExecutionEventObserver observer = new CodexExecutionEventObserver(
                new CodexAgentExecutionEventMapper(this.json), callbacks);
        observer.bindThread("thread-1");
        observer.activate("turn-1");
        observer.observe("thread/tokenUsage/updated", this.json.readTree(
                "{\"threadId\":\"other\",\"turnId\":\"turn-1\",\"tokenUsage\":{}}"));

        observer.complete();
        observer.complete();

        assertThat(callbacks.events).hasSize(1);
        assertThat(callbacks.completed).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(AgentExecutionEventType.TURN);
            assertThat(event.status()).isEqualTo(AgentExecutionEventStatus.COMPLETED);
            assertThat(event.providerEventKey()).isEqualTo("turn:turn-1:completed");
        });
    }

    @Test
    void acceptsDirectTurnIdentityForPlanUpdates() throws Exception {
        final RecordingCallbacks callbacks = new RecordingCallbacks();
        final CodexExecutionEventObserver observer = new CodexExecutionEventObserver(
                new CodexAgentExecutionEventMapper(this.json), callbacks);
        observer.bindThread("thread-1");
        observer.observe("turn/plan/updated", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","plan":[{"step":"Patch","status":"inProgress"}]}
                """));
        observer.activate("turn-1");

        assertThat(callbacks.events).extracting(AgentExecutionEventCandidate::type)
                .containsExactly(AgentExecutionEventType.TURN, AgentExecutionEventType.PLAN);
    }

    @Test
    void mappingFailureDegradesCaptureWithoutEscapingNotificationHandling() throws Exception {
        final RecordingCallbacks callbacks = new RecordingCallbacks();
        final CodexAgentExecutionEventMapper mapper = mock(CodexAgentExecutionEventMapper.class);
        final var params = this.json.readTree("{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\"}");
        when(mapper.map(eq("warning"), eq(params), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new CodexTransportException("invalid event"));
        final CodexExecutionEventObserver observer = new CodexExecutionEventObserver(mapper, callbacks);
        observer.bindThread("thread-1");
        observer.activate("turn-1");

        assertThatCode(() -> observer.observe("warning", params)).doesNotThrowAnyException();

        assertThat(callbacks.degraded).isOne();
    }

    private static final class RecordingCallbacks implements CodexExecutionIdentityCallbacks {
        private final List<AgentExecutionEventCandidate> events = new ArrayList<>();
        private final List<AgentExecutionEventCandidate> completed = new ArrayList<>();
        private int degraded;

        @Override public void conversationStarted(final String threadId, final String providerVersion) { }
        @Override public void turnStarted(final String turnId) { }
        @Override public void executionEvent(final AgentExecutionEventCandidate event) { this.events.add(event); }
        @Override public void eventCaptureCompleted(final AgentExecutionEventCandidate event) { this.completed.add(event); }
        @Override public void eventCaptureDegraded(final RuntimeException failure) { this.degraded++; }
    }
}
