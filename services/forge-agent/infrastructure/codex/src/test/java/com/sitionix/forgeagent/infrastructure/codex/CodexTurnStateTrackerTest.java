package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CodexTurnStateTrackerTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void standaloneNativeChildrenAreAcceptedOnlyUnderExplicitPolicy() throws Exception {
        for(String type:java.util.List.of("subAgentActivity","collabAgentToolCall")){
            for(boolean allowed:java.util.List.of(false,true)){
                var tracker=new CodexTurnStateTracker(allowed);var state=tracker.register("root");tracker.bindTurnId(state,"turn");
                tracker.handleNotification("item/completed",json.createObjectNode().put("threadId","root").put("turnId","turn").set("item",json.createObjectNode().put("id","child-event").put("type",type)));
                assertThat(state.done()).isEqualTo(!allowed);
            }
        }
    }
    @Test
    void providerErrorStillUsesTopLevelReasonWhenNestedErrorHasOnlyACode() throws Exception {
        var tracker = new CodexTurnStateTracker();
        var state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");
        tracker.handleNotification("error", json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","message":"Provider rejected request",
                 "error":{"codexErrorInfo":"badRequest"}}
                """));
        assertThatThrownBy(() -> state.result().join()).cause().hasMessage("Provider rejected request");
    }

    @Test
    void providerFailureKeepsItsCodeAndSafeReasonInTheExecutionException() throws Exception {
        var tracker = new CodexTurnStateTracker();
        var state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");
        tracker.handleNotification("error", json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","willRetry":false,"error":{
                 "message":"Sign in again. TOKEN=fixture-secret","codexErrorInfo":"unauthorized"}}
                """));
        assertThatThrownBy(() -> state.result().join()).cause()
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ForgeAgentException.class)
                .hasMessage("Sign in again. TOKEN=[REDACTED]")
                .satisfies(error -> assertThat(((com.sitionix.forgeagent.domain.exception.ForgeAgentException) error).code())
                        .isEqualTo("unauthorized"));
    }

    @Test
    void failedTurnCompletionKeepsAnUpstreamCodeWithoutAnErrorNotification() throws Exception {
        var tracker = new CodexTurnStateTracker();
        var state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");
        tracker.handleNotification("turn/completed", json.readTree("""
                {"threadId":"thread-1","turn":{"id":"turn-1","status":"failed","error":{
                 "message":"Bad model","codexErrorInfo":{"badRequest":{"httpStatusCode":400}}}}}
                """));
        assertThatThrownBy(() -> state.result().join()).cause()
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ForgeAgentException.class)
                .satisfies(error -> assertThat(((com.sitionix.forgeagent.domain.exception.ForgeAgentException) error).code())
                        .isEqualTo("badRequest"));
    }

    @Test
    void notificationsAreBufferedUntilThePersistedTurnIdentityIsBound() throws Exception {
        final CodexTurnStateTracker tracker=new CodexTurnStateTracker();
        final CodexExecutionState state=tracker.register("thread-1");
        tracker.handleNotification("item/completed", json.readTree("{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{\"id\":\"message-1\",\"type\":\"agentMessage\",\"phase\":\"final_answer\",\"text\":\"done\"}}"));
        tracker.handleNotification("turn/started", json.readTree("{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\"}}"));
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
        tracker.handleNotification("turn/started", this.json.readTree(
                "{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\"}}"));

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
        tracker.handleNotification("turn/started", this.json.readTree(
                "{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\"}}"));

        tracker.handleNotification("thread/status/changed", this.json.readTree(
                "{\"threadId\":\"thread-1\",\"status\":{\"type\":\"idle\"}}"));

        assertThatThrownBy(state.result()::join).hasRootCauseMessage("Codex execution failed.");
    }

    @Test
    void idleBeforeTargetTurnStartedFailsExplicitly() throws Exception {
        final CodexTurnStateTracker tracker = new CodexTurnStateTracker();
        final CodexExecutionState state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");

        tracker.handleNotification("thread/status/changed", this.json.readTree(
                "{\"threadId\":\"thread-1\",\"status\":{\"type\":\"idle\"}}"));

        assertThatThrownBy(state.result()::join)
                .hasRootCauseMessage("Codex thread became idle before the target turn started.");
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

    @Test
    void failedCommandItemDoesNotFailTheTurnAndAgentMayRecover() throws Exception {
        final CodexTurnStateTracker tracker = new CodexTurnStateTracker();
        final CodexExecutionState state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");

        tracker.handleNotification("item/completed", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","item":{"id":"tool-1","type":"commandExecution","status":"failed","error":{"message":"command failed"}}}
                """));

        assertThat(state.result()).isNotDone();
        tracker.handleNotification("turn/started", this.json.readTree(
                "{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\"}}"));
        tracker.handleNotification("item/completed", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","item":{"id":"message-1","type":"agentMessage","phase":"final_answer","text":"fixed"}}
                """));
        tracker.handleNotification("thread/status/changed", this.json.readTree(
                "{\"threadId\":\"thread-1\",\"status\":{\"type\":\"idle\"}}"));
        assertThat(state.result()).isCompletedWithValue("fixed");
    }

    @Test
    void targetScopedProviderErrorFailsTheExactTurn() throws Exception {
        final CodexTurnStateTracker tracker = new CodexTurnStateTracker();
        final CodexExecutionState state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");

        tracker.handleNotification("error", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","error":{"message":"provider failed"}}
                """));

        assertThatThrownBy(state.result()::join).hasRootCauseMessage("provider failed");
    }

    @Test
    void retryableProviderErrorDoesNotTerminateTheTurn() throws Exception {
        final CodexTurnStateTracker tracker = new CodexTurnStateTracker();
        final CodexExecutionState state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");
        tracker.handleNotification("turn/started", this.json.readTree(
                "{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\"}}"));

        tracker.handleNotification("error", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","willRetry":true,
                 "error":{"message":"transient provider failure","codexErrorInfo":"serverOverloaded"}}
                """));

        assertThat(state.result()).isNotDone();

        tracker.handleNotification("item/completed", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1",
                 "item":{"type":"agentMessage","phase":"final_answer","text":"recovered"}}
                """));
        tracker.handleNotification("thread/status/changed", this.json.readTree(
                "{\"threadId\":\"thread-1\",\"status\":{\"type\":\"idle\"}}"));

        assertThat(state.result()).isCompletedWithValue("recovered");
    }

    @Test
    void malformedRetryMarkerFailsClosed() throws Exception {
        final CodexTurnStateTracker tracker = new CodexTurnStateTracker();
        final CodexExecutionState state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");

        assertThatThrownBy(() -> tracker.handleNotification("error", this.json.readTree("""
                {"threadId":"thread-1","turnId":"turn-1","willRetry":"yes",
                 "error":{"message":"invalid wire shape"}}
                """))).hasMessage("Codex execution failed.");
    }

    @Test
    void unscopedProviderErrorIsNotMisattributedToAnActiveTurn() throws Exception {
        final CodexTurnStateTracker tracker = new CodexTurnStateTracker();
        final CodexExecutionState state = tracker.register("thread-1");
        tracker.bindTurnId(state, "turn-1");

        tracker.handleNotification("error", this.json.readTree("{\"message\":\"global diagnostic\"}"));

        assertThat(state.result()).isNotDone();
    }
}
