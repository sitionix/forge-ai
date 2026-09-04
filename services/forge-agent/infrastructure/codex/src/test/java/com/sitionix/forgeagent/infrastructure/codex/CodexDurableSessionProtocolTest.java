package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CodexDurableSessionProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void durableThreadCreationUsesEphemeralFalseAndExtractsThreadIdentity() throws Exception {
        final Harness harness = this.harness();
        final JsonNode params = this.objectMapper.readTree("""
                {"cwd":"/workspace","model":"gpt-5.6-sol","approvalPolicy":"never","sandbox":"workspace-write"}
                """);
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> harness.protocol().startDurableThread(
                harness.transport(), params, Duration.ofSeconds(1)));

        final JsonNode request = this.readRequest(harness.process());
        assertThat(request.path("method").asText()).isEqualTo("thread/start");
        assertThat(request.path("params")).isEqualTo(this.objectMapper.readTree("""
                {"cwd":"/workspace","model":"gpt-5.6-sol","approvalPolicy":"never","sandbox":"workspace-write","ephemeral":false}
                """));
        this.reply(harness.process(), request, "{\"thread\":{\"id\":\"thread-durable-1\"}}");

        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("thread-durable-1");
        harness.transport().close();
    }

    @Test
    void resumeUsesOnlyPersistedThreadIdentityAndExtractsSameIdentity() throws Exception {
        final Harness harness = this.harness();
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> harness.protocol().resumeThread(
                harness.transport(), "thread-durable-1", Duration.ofSeconds(1)));

        final JsonNode request = this.readRequest(harness.process());
        assertThat(request.path("method").asText()).isEqualTo("thread/resume");
        assertThat(request.path("params")).isEqualTo(this.objectMapper.readTree(
                "{\"threadId\":\"thread-durable-1\",\"excludeTurns\":true}"
        ));
        this.reply(harness.process(), request, "{\"thread\":{\"id\":\"thread-durable-1\"}}");

        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("thread-durable-1");
        harness.transport().close();
    }

    @Test
    void resumeRejectsMismatchedResponseIdentityWithoutStartingFreshThread() throws Exception {
        final Harness harness = this.harness();
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> harness.protocol().resumeThread(
                harness.transport(), "thread-durable-1", Duration.ofSeconds(1)));
        final JsonNode resume = this.readRequest(harness.process());
        this.reply(harness.process(), resume, "{\"thread\":{\"id\":\"different-thread\"}}");

        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CodexTransportException.class)
                .hasRootCauseMessage("Codex resume returned thread.id different-thread for requested thread-durable-1");
        assertThat(harness.process().pendingClientRequestBytes()).isZero();
        harness.transport().close();
    }

    @Test
    void resumedTurnUsesThreadIdentityAndExtractsTurnIdentity() throws Exception {
        final Harness harness = this.harness();
        final JsonNode params = this.objectMapper.readTree("""
                {"threadId":"thread-durable-1","input":[{"type":"text","text":"Continue.","text_elements":[]}]}
                """);
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> harness.protocol().startTurn(
                harness.transport(), params, Duration.ofSeconds(1)));

        final JsonNode request = this.readRequest(harness.process());
        assertThat(request.path("method").asText()).isEqualTo("turn/start");
        assertThat(request.path("params")).isEqualTo(params);
        this.reply(harness.process(), request, "{\"turn\":{\"id\":\"turn-2\",\"status\":\"inProgress\",\"items\":[]}}");

        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("turn-2");
        harness.transport().close();
    }

    @Test
    void malformedThreadAndTurnResponsesFailExplicitly() throws Exception {
        assertThatThrownBy(() -> CodexSessionProtocol.requireThreadId(this.objectMapper.readTree("{\"thread\":{}}")))
                .isInstanceOf(CodexTransportException.class)
                .hasMessage("Codex thread response did not include a valid thread.id");
        assertThatThrownBy(() -> CodexSessionProtocol.requireTurnId(this.objectMapper.readTree("{\"turn\":{\"id\":\" \"}}")))
                .isInstanceOf(CodexTransportException.class)
                .hasMessage("Codex turn response did not include a valid turn.id");
    }

    @Test
    void failedResumePropagatesErrorWithoutStartingFreshThread() throws Exception {
        final Harness harness = this.harness();
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> harness.protocol().resumeThread(
                harness.transport(), "missing-thread", Duration.ofSeconds(1)));
        final JsonNode resume = this.readRequest(harness.process());
        harness.process().writeStdout("{\"id\":\"" + resume.path("id").asText()
                + "\",\"error\":{\"code\":-32600,\"message\":\"no rollout found for thread id missing-thread\"}}");

        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CodexRemoteException.class)
                .rootCause()
                .hasMessageContaining("code=-32600")
                .hasMessageContaining("no rollout found for thread id missing-thread");
        assertThat(harness.process().pendingClientRequestBytes()).isZero();
        harness.transport().close();
    }

    @Test
    void structuredNotificationsExposeCorrelationIdentityAndEventKind() throws Exception {
        final java.util.Set<String> observedKinds = new java.util.HashSet<>();
        for (final String fixture : List.of(
                "{\"method\":\"turn/plan/updated\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"plan\":[]}}",
                "{\"method\":\"item/started\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{\"id\":\"item-1\",\"type\":\"reasoning\"}}}",
                "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{\"id\":\"item-2\",\"type\":\"commandExecution\"}}}",
                "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{\"id\":\"item-3\",\"type\":\"fileChange\"}}}",
                "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{\"id\":\"item-4\",\"type\":\"mcpToolCall\"}}}",
                "{\"method\":\"thread/tokenUsage/updated\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"tokenUsage\":{}}}",
                "{\"method\":\"thread/compacted\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\"}}",
                "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\",\"status\":\"failed\"}}}"
        )) {
            final CodexStructuredNotification notification = CodexStructuredNotification.parse(this.objectMapper.readTree(fixture));
            assertThat(notification.threadId()).isEqualTo("thread-1");
            assertThat(notification.method()).isNotBlank();
            observedKinds.add(notification.itemType() == null ? notification.method() : notification.itemType());
        }
        assertThat(observedKinds).containsExactlyInAnyOrder(
                "turn/plan/updated", "reasoning", "commandExecution", "fileChange", "mcpToolCall",
                "thread/tokenUsage/updated", "thread/compacted", "turn/completed"
        );

        final CodexStructuredNotification warning = CodexStructuredNotification.parse(this.objectMapper.readTree(
                "{\"method\":\"warning\",\"params\":{\"message\":\"provider warning\"}}"
        ));
        assertThat(warning.threadId()).isNull();
        assertThat(warning.turnId()).isNull();

        final CodexStructuredNotification threadStarted = CodexStructuredNotification.parse(this.objectMapper.readTree(
                "{\"method\":\"thread/started\",\"params\":{\"thread\":{\"id\":\"thread-1\"}}}"
        ));
        final CodexStructuredNotification threadIdle = CodexStructuredNotification.parse(this.objectMapper.readTree(
                "{\"method\":\"thread/status/changed\",\"params\":{\"threadId\":\"thread-1\",\"status\":{\"type\":\"idle\"}}}"
        ));
        final CodexStructuredNotification turnStarted = CodexStructuredNotification.parse(this.objectMapper.readTree(
                "{\"method\":\"turn/started\",\"params\":{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\"}}}"
        ));
        final CodexStructuredNotification delta = CodexStructuredNotification.parse(this.objectMapper.readTree(
                "{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"itemId\":\"item-1\",\"delta\":\"OK\"}}"
        ));
        final CodexStructuredNotification notice = CodexStructuredNotification.parse(this.objectMapper.readTree(
                "{\"method\":\"deprecationNotice\",\"params\":{\"summary\":\"deprecated\",\"details\":null}}"
        ));
        final CodexStructuredNotification mcpStatus = CodexStructuredNotification.parse(this.objectMapper.readTree(
                "{\"method\":\"mcpServer/startupStatus/updated\",\"params\":{\"threadId\":\"thread-1\",\"name\":\"codex_apps\",\"status\":\"ready\"}}"
        ));
        final CodexStructuredNotification goalCleared = CodexStructuredNotification.parse(this.objectMapper.readTree(
                "{\"method\":\"thread/goal/cleared\",\"params\":{\"threadId\":\"thread-1\"}}"
        ));

        assertThat(threadStarted.threadId()).isEqualTo("thread-1");
        assertThat(threadStarted.turnId()).isNull();
        assertThat(threadIdle.threadId()).isEqualTo("thread-1");
        assertThat(threadIdle.turnId()).isNull();
        assertThat(turnStarted.turnId()).isEqualTo("turn-1");
        assertThat(delta.itemId()).isEqualTo("item-1");
        assertThat(notice.threadId()).isNull();
        assertThat(notice.turnId()).isNull();
        assertThat(mcpStatus.threadId()).isEqualTo("thread-1");
        assertThat(mcpStatus.turnId()).isNull();
        assertThat(goalCleared.threadId()).isEqualTo("thread-1");
        assertThat(goalCleared.turnId()).isNull();
    }

    @Test
    void identityBearingNotificationWithoutThreadOrTurnFailsExplicitly() throws Exception {
        assertThatThrownBy(() -> CodexStructuredNotification.parse(this.objectMapper.readTree("""
                {"method":"item/completed","params":{"threadId":"thread-1","item":{"id":"item-1","type":"agentMessage"}}}
                """)))
                .isInstanceOf(CodexTransportException.class)
                .hasMessage("Codex notification item/completed did not include valid thread/turn identity");
    }

    private Harness harness() {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setGracefulTerminateTimeout(Duration.ofMillis(20));
        properties.setForceKillTimeout(Duration.ofMillis(20));
        final CodexJsonRpcTransport transport = new CodexJsonRpcTransport(
                this.objectMapper,
                new StartedCodexAppServer(process, List.of("codex", "app-server", "--stdio"), Instant.now()),
                properties
        );
        return new Harness(process, transport, new CodexSessionProtocol(this.objectMapper));
    }

    private JsonNode readRequest(final FakeCodexProcess process) throws Exception {
        return this.objectMapper.readTree(process.readRequest());
    }

    private void reply(final FakeCodexProcess process, final JsonNode request, final String result) {
        process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":" + result + "}");
    }

    private record Harness(FakeCodexProcess process, CodexJsonRpcTransport transport, CodexSessionProtocol protocol) {
    }
}
