package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CodexTurnStateTrackerTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test
    void notificationsAreBufferedUntilThePersistedTurnIdentityIsBound() throws Exception {
        final CodexTurnStateTracker tracker=new CodexTurnStateTracker();
        final CodexExecutionState state=tracker.register("thread-1");
        tracker.handleNotification("item/completed", json.readTree("{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{\"id\":\"message-1\",\"type\":\"agentMessage\",\"phase\":\"final_answer\",\"text\":\"done\"}}"));
        tracker.handleNotification("thread/status/changed", json.readTree("{\"threadId\":\"thread-1\",\"status\":{\"type\":\"idle\"}}"));
        assertThat(state.result()).isNotDone();
        tracker.bindTurnId(state,"turn-1");
        assertThat(state.result()).isCompletedWithValue("done");
    }

    @Test
    void live01532SequenceCompletesOnlyWhenTheTargetThreadBecomesIdle() throws Exception {
        final CodexTurnStateTracker tracker = new CodexTurnStateTracker();
        final CodexExecutionState state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");

        tracker.handleNotification("item/completed", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","phase":"commentary","text":"intermediate"}}
                """));
        assertThat(state.result()).isNotDone();
        tracker.handleNotification("item/completed", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","phase":"final_answer","text":"final"}}
                """));
        assertThat(state.result()).isNotDone();

        tracker.handleNotification("thread/status/changed", this.json.readTree(
                "{\"threadId\":\"thread-1\",\"status\":{\"type\":\"idle\"}}"));

        assertThat(state.result()).isCompletedWithValue("final");
    }

    @Test
    void schemaTurnCompletedRemainsSupportedAndCorrelated() throws Exception {
        final CodexTurnStateTracker tracker = new CodexTurnStateTracker();
        final CodexExecutionState state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");
        tracker.handleNotification("item/completed", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","phase":"final_answer","text":"done"}}
                """));

        tracker.handleNotification("turn/completed", this.json.readTree("""
                {"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}
                """));

        assertThat(state.result()).isCompletedWithValue("done");
    }

    @Test
    void wrongTurnIdentityFailsTheTrackedExecution() throws Exception {
        final CodexTurnStateTracker tracker = new CodexTurnStateTracker();
        final CodexExecutionState state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");

        tracker.handleNotification("item/completed", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-other","item":{"type":"agentMessage","phase":"final_answer","text":"wrong"}}
                """));

        assertThatThrownBy(state.result()::join)
                .hasRootCauseMessage("Codex turn id changed for active thread");
    }

    @Test
    void idleWithoutFinalOutputFailsExplicitly() throws Exception {
        final CodexTurnStateTracker tracker = new CodexTurnStateTracker();
        final CodexExecutionState state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");

        tracker.handleNotification("thread/status/changed", this.json.readTree(
                "{\"threadId\":\"thread-1\",\"status\":{\"type\":\"idle\"}}"));

        assertThatThrownBy(state.result()::join).hasRootCauseMessage("Codex execution failed.");
    }

    @Test
    void failedTurnPropagatesProviderFailure() throws Exception {
        final CodexTurnStateTracker tracker = new CodexTurnStateTracker();
        final CodexExecutionState state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");

        tracker.handleNotification("turn/completed", this.json.readTree("""
                {"threadId":"thread-1","turn":{"id":"turn-1","status":"failed","error":{"message":"model failed"}}}
                """));

        assertThatThrownBy(state.result()::join).hasRootCauseMessage("model failed");
    }
}
