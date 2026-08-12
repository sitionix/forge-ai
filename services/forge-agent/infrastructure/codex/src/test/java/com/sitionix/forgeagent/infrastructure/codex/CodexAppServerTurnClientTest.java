package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.AgentExecutionException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CodexAppServerTurnClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executeTurnSendsExactThreadAndTurnProtocolWithNativeOutputSchema() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final JsonNode schema = this.schema();
        final CompletableFuture<CodexTurnResult> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Analyze auth.", "gpt-5.6-luna", "high", schema)
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        assertThat(threadStart.path("method").asText()).isEqualTo("thread/start");
        assertThat(threadStart.path("params").path("model").asText()).isEqualTo("gpt-5.6-luna");
        assertThat(threadStart.path("params").path("approvalPolicy").asText()).isEqualTo("never");
        assertThat(threadStart.path("params").path("sandbox").asText()).isEqualTo("read-only");
        assertThat(threadStart.path("params").path("ephemeral").asBoolean()).isTrue();
        assertThat(threadStart.path("params").path("cwd").asText()).isNotBlank();
        process.writeStdout("{\"id\":\"" + threadStart.path("id").asText() + "\",\"result\":{\"threadId\":\"thread-1\"}}");

        final JsonNode turnStart = this.readRequest(process);
        assertThat(turnStart.path("method").asText()).isEqualTo("turn/start");
        assertThat(turnStart.path("params").path("threadId").asText()).isEqualTo("thread-1");
        assertThat(turnStart.path("params").path("input")).isEqualTo(this.objectMapper.readTree("[{\"type\":\"text\",\"text\":\"Analyze auth.\"}]"));
        assertThat(turnStart.path("params").path("model").asText()).isEqualTo("gpt-5.6-luna");
        assertThat(turnStart.path("params").path("effort").asText()).isEqualTo("high");
        assertThat(turnStart.path("params").path("outputSchema")).isEqualTo(schema);
        assertThat(turnStart.path("params").toString()).doesNotContain("\"outputSchema\":{\"json\"");
        process.writeStdout("{\"id\":\"" + turnStart.path("id").asText() + "\",\"result\":{\"turnId\":\"turn-1\"}}");
        this.awaitActiveTurn(client);
        this.complete(process, "thread-1", "turn-1", "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");

        assertThat(result.get(1, TimeUnit.SECONDS))
                .isEqualTo(new CodexTurnResult("thread-1", "turn-1", "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}"));
        client.close();
    }

    @Test
    void nullEffortIsOmittedFromTurnStart() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final JsonNode schema = this.schema();
        final CompletableFuture<CodexTurnResult> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Analyze auth.", "gpt-5.6-luna", null, schema)
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + threadStart.path("id").asText() + "\",\"result\":{\"threadId\":\"thread-1\"}}");
        final JsonNode turnStart = this.readRequest(process);

        assertThat(turnStart.path("params").has("effort")).isFalse();
        process.writeStdout("{\"id\":\"" + turnStart.path("id").asText() + "\",\"result\":{\"turnId\":\"turn-1\"}}");
        this.awaitActiveTurn(client);
        this.complete(process, "thread-1", "turn-1", "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");
        assertThat(result.get(1, TimeUnit.SECONDS).outputText()).contains("OK");
        client.close();
    }

    @Test
    void completedAgentMessageAndTurnCompletedProduceResult() throws Exception {
        final TurnHarness harness = this.startedTurn();

        this.complete(harness.process(), "thread-1", "turn-1", "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");

        assertThat(harness.result().get(1, TimeUnit.SECONDS).outputText()).isEqualTo("{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");
        harness.client().close();
    }

    @Test
    void failedTurnFailsSafely() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"status\":\"failed\"}}");

        assertAgentExecutionFailure(harness.result(), "CODEX_EXECUTION_FAILED");
        harness.client().close();
    }

    @Test
    void interruptedTurnFailsSafely() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"status\":\"interrupted\"}}");

        assertAgentExecutionFailure(harness.result(), "CODEX_EXECUTION_FAILED");
        harness.client().close();
    }

    @Test
    void completedWithoutAgentMessageFailsSafely() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"status\":\"completed\"}}");

        assertAgentExecutionFailure(harness.result(), "CODEX_EXECUTION_FAILED");
        harness.client().close();
    }

    @Test
    void forbiddenToolItemFailsSafely() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{\"type\":\"commandExecution\",\"text\":\"nope\"}}}");
        this.assertInterrupt(harness.process(), "thread-1", "turn-1");

        assertAgentExecutionFailure(harness.result(), "CODEX_EXECUTION_FAILED");
        harness.client().close();
    }

    @Test
    void forbiddenStartedToolItemInterruptsTurnAndFailsSafely() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout("{\"method\":\"item/started\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{\"type\":\"commandExecution\"}}}");
        this.assertInterrupt(harness.process(), "thread-1", "turn-1");

        assertAgentExecutionFailure(harness.result(), "CODEX_EXECUTION_FAILED");
        assertThat(harness.client().activeTurnCountForTesting()).isZero();
        assertThat(harness.client().bufferedNotificationCountForTesting()).isZero();
        harness.client().close();
    }

    @Test
    void commandApprovalRequestIsDeclinedAndFailsTurnSafely() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout("{\"id\":\"approval-1\",\"method\":\"item/commandExecution/requestApproval\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\"}}");
        final JsonNode approvalResponse = this.readRequest(harness.process());

        assertThat(approvalResponse.path("id").asText()).isEqualTo("approval-1");
        assertThat(approvalResponse.path("result").path("decision").asText()).isEqualTo("decline");
        this.assertInterrupt(harness.process(), "thread-1", "turn-1");
        assertAgentExecutionFailure(harness.result(), "CODEX_EXECUTION_FAILED");
        harness.client().close();
    }

    @Test
    void lateNotificationsAfterPolicyViolationCannotResurrectTurnOrBufferOrphans() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout("{\"method\":\"item/started\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{\"type\":\"commandExecution\"}}}");
        this.assertInterrupt(harness.process(), "thread-1", "turn-1");
        assertAgentExecutionFailure(harness.result(), "CODEX_EXECUTION_FAILED");

        this.complete(harness.process(), "thread-1", "turn-1", "{\"summary\":\"Late\",\"riskLevel\":\"LOW\"}");

        assertThat(harness.client().activeTurnCountForTesting()).isZero();
        assertThat(harness.client().bufferedNotificationCountForTesting()).isZero();
        harness.client().close();
    }

    @Test
    void transportFailureFailsActiveTurnWaiter() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().closeStdout();

        assertAgentExecutionFailure(harness.result(), "CODEX_EXECUTION_FAILED");
    }

    @Test
    void transportFailureDuringRegistrationHandoffFailsPromptlyAndLeavesNoActiveTurn() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerProperties properties = this.properties();
        properties.setTurnTimeout(Duration.ofSeconds(30));
        final CodexAppServerClient client = this.client(new FakeStarter(process), properties);
        final CompletableFuture<CodexTurnResult> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Handoff.", "model-a", null, this.schemaUnchecked())
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + threadStart.path("id").asText() + "\",\"result\":{\"threadId\":\"thread-1\"}}");
        final JsonNode turnStart = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + turnStart.path("id").asText() + "\",\"result\":{\"turnId\":\"turn-1\"}}");
        process.terminateNow();

        assertAgentExecutionFailure(result, "CODEX_EXECUTION_FAILED");
        assertThat(client.activeTurnCountForTesting()).isZero();
        assertThat(client.bufferedNotificationCountForTesting()).isZero();
    }

    @Test
    void unknownTerminalStatusFailsSafely() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"status\":\"done\"}}");

        assertAgentExecutionFailure(harness.result(), "CODEX_EXECUTION_FAILED");
        harness.client().close();
    }

    @Test
    void earlyNotificationsBeforeTurnStartResponseAreReplayed() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final JsonNode schema = this.schema();
        final CompletableFuture<CodexTurnResult> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Race.", "model-a", null, schema)
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + threadStart.path("id").asText() + "\",\"result\":{\"threadId\":\"thread-1\"}}");
        final JsonNode turnStart = this.readRequest(process);
        process.writeStdout("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{\"type\":\"agentMessage\",\"text\":\"{\\\"summary\\\":\\\"Early\\\",\\\"riskLevel\\\":\\\"LOW\\\"}\"}}}");
        process.writeStdout("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"status\":\"completed\"}}");
        process.writeStdout("{\"id\":\"" + turnStart.path("id").asText() + "\",\"result\":{\"turnId\":\"turn-1\"}}");

        assertThat(result.get(1, TimeUnit.SECONDS).outputText()).isEqualTo("{\"summary\":\"Early\",\"riskLevel\":\"LOW\"}");
        client.close();
    }

    @Test
    void turnTimeoutInterruptsAndFailsSafely() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerProperties properties = this.properties();
        properties.setTurnTimeout(Duration.ofMillis(40));
        final CodexAppServerClient client = this.client(new FakeStarter(process), properties);
        final JsonNode schema = this.schema();
        final CompletableFuture<CodexTurnResult> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Slow.", "model-a", null, schema)
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + threadStart.path("id").asText() + "\",\"result\":{\"threadId\":\"thread-1\"}}");
        final JsonNode turnStart = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + turnStart.path("id").asText() + "\",\"result\":{\"turnId\":\"turn-1\"}}");
        final JsonNode interrupt = this.readRequest(process);
        assertThat(interrupt.path("method").asText()).isEqualTo("turn/interrupt");
        assertThat(interrupt.path("params").path("threadId").asText()).isEqualTo("thread-1");
        assertThat(interrupt.path("params").path("turnId").asText()).isEqualTo("turn-1");
        process.writeStdout("{\"id\":\"" + interrupt.path("id").asText() + "\",\"result\":{}}");

        assertAgentExecutionFailure(result, "CODEX_EXECUTION_TIMEOUT");
        process.writeStdout("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"status\":\"interrupted\"}}");
        assertThat(client.activeTurnCountForTesting()).isZero();
        assertThat(client.bufferedNotificationCountForTesting()).isZero();
        client.close();
    }

    @Test
    void concurrentTurnsShareOneProcessAndReceiveOnlyTheirOwnOutput() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerClient client = this.client(starter, this.properties());
        final JsonNode schema = this.schema();
        final CompletableFuture<CodexTurnResult> b = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt B", "model-b", null, schema)
        ));
        final CompletableFuture<CodexTurnResult> c = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt C", "model-c", null, schema)
        ));

        this.initialize(process);
        final JsonNode threadOne = this.readRequest(process);
        final JsonNode threadTwo = this.readRequest(process);
        this.replyThread(process, threadOne);
        this.replyThread(process, threadTwo);
        final JsonNode turnOne = this.readRequest(process);
        final JsonNode turnTwo = this.readRequest(process);
        this.replyTurn(process, turnOne);
        this.replyTurn(process, turnTwo);
        this.awaitActiveTurns(client, 2);
        this.complete(process, "thread-c", "turn-c", "{\"summary\":\"C\",\"riskLevel\":\"LOW\"}");
        this.complete(process, "thread-b", "turn-b", "{\"summary\":\"B\",\"riskLevel\":\"HIGH\"}");

        assertThat(b.get(1, TimeUnit.SECONDS)).isEqualTo(new CodexTurnResult("thread-b", "turn-b", "{\"summary\":\"B\",\"riskLevel\":\"HIGH\"}"));
        assertThat(c.get(1, TimeUnit.SECONDS)).isEqualTo(new CodexTurnResult("thread-c", "turn-c", "{\"summary\":\"C\",\"riskLevel\":\"LOW\"}"));
        assertThat(starter.starts()).isEqualTo(1);
        client.close();
    }

    @Test
    void concurrentTurnsWithEarlyNotificationsRemainIsolated() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerClient client = this.client(starter, this.properties());
        final JsonNode schema = this.schema();
        final CompletableFuture<CodexTurnResult> b = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt B", "model-b", null, schema)
        ));
        final CompletableFuture<CodexTurnResult> c = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt C", "model-c", null, schema)
        ));

        this.initialize(process);
        final JsonNode threadOne = this.readRequest(process);
        final JsonNode threadTwo = this.readRequest(process);
        this.replyThread(process, threadOne);
        this.replyThread(process, threadTwo);
        final JsonNode turnOne = this.readRequest(process);
        final JsonNode turnTwo = this.readRequest(process);
        process.writeStdout("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"thread-b\",\"turnId\":\"turn-b\",\"item\":{\"type\":\"agentMessage\",\"text\":\"{\\\"summary\\\":\\\"B early\\\",\\\"riskLevel\\\":\\\"LOW\\\"}\"}}}");
        process.writeStdout("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-b\",\"turnId\":\"turn-b\",\"status\":\"completed\"}}");
        process.writeStdout("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"thread-c\",\"turnId\":\"turn-c\",\"item\":{\"type\":\"agentMessage\",\"text\":\"{\\\"summary\\\":\\\"C early\\\",\\\"riskLevel\\\":\\\"HIGH\\\"}\"}}}");
        process.writeStdout("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-c\",\"turnId\":\"turn-c\",\"status\":\"completed\"}}");
        this.replyTurn(process, turnTwo);
        this.replyTurn(process, turnOne);

        assertThat(b.get(1, TimeUnit.SECONDS)).isEqualTo(new CodexTurnResult("thread-b", "turn-b", "{\"summary\":\"B early\",\"riskLevel\":\"LOW\"}"));
        assertThat(c.get(1, TimeUnit.SECONDS)).isEqualTo(new CodexTurnResult("thread-c", "turn-c", "{\"summary\":\"C early\",\"riskLevel\":\"HIGH\"}"));
        assertThat(client.activeTurnCountForTesting()).isZero();
        assertThat(client.bufferedNotificationCountForTesting()).isZero();
        assertThat(starter.starts()).isEqualTo(1);
        client.close();
    }

    private TurnHarness startedTurn() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final JsonNode schema = this.schema();
        final CompletableFuture<CodexTurnResult> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Analyze auth.", "model-a", null, schema)
        ));
        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + threadStart.path("id").asText() + "\",\"result\":{\"threadId\":\"thread-1\"}}");
        final JsonNode turnStart = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + turnStart.path("id").asText() + "\",\"result\":{\"turnId\":\"turn-1\"}}");
        this.awaitActiveTurn(client);
        return new TurnHarness(client, process, result);
    }

    private void awaitActiveTurn(final CodexAppServerClient client) {
        this.awaitActiveTurns(client, 1);
    }

    private void awaitActiveTurns(final CodexAppServerClient client, final int expected) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (client.activeTurnCountForTesting() < expected && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(client.activeTurnCountForTesting()).isEqualTo(expected);
    }

    private void initialize(final FakeCodexProcess process) throws Exception {
        final JsonNode initialize = this.readRequest(process);
        assertThat(initialize.path("method").asText()).isEqualTo("initialize");
        process.writeStdout("{\"id\":\"" + initialize.path("id").asText() + "\",\"result\":{\"userAgent\":\"codex/1\"}}");
        final JsonNode initialized = this.readRequest(process);
        assertThat(initialized.path("method").asText()).isEqualTo("initialized");
    }

    private void complete(final FakeCodexProcess process, final String threadId, final String turnId, final String output) {
        final String escaped = output.replace("\\", "\\\\").replace("\"", "\\\"");
        process.writeStdout("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"" + threadId + "\",\"turnId\":\"" + turnId + "\",\"item\":{\"type\":\"agentMessage\",\"text\":\"" + escaped + "\"}}}");
        process.writeStdout("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"" + threadId + "\",\"turnId\":\"" + turnId + "\",\"status\":\"completed\"}}");
    }

    private void assertInterrupt(final FakeCodexProcess process, final String threadId, final String turnId) throws Exception {
        final JsonNode interrupt = this.readRequest(process);
        assertThat(interrupt.path("method").asText()).isEqualTo("turn/interrupt");
        assertThat(interrupt.path("params").path("threadId").asText()).isEqualTo(threadId);
        assertThat(interrupt.path("params").path("turnId").asText()).isEqualTo(turnId);
        process.writeStdout("{\"id\":\"" + interrupt.path("id").asText() + "\",\"result\":{}}");
    }

    private void replyThread(final FakeCodexProcess process, final JsonNode request) {
        final String model = request.path("params").path("model").asText();
        final String threadId = "model-b".equals(model) ? "thread-b" : "thread-c";
        process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":{\"threadId\":\"" + threadId + "\"}}");
    }

    private void replyTurn(final FakeCodexProcess process, final JsonNode request) {
        final String threadId = request.path("params").path("threadId").asText();
        final String turnId = "thread-b".equals(threadId) ? "turn-b" : "turn-c";
        process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":{\"turnId\":\"" + turnId + "\"}}");
    }

    private JsonNode schema() throws Exception {
        return this.objectMapper.readTree("""
                {
                  "type": "object",
                  "description": "Technical analysis result.",
                  "properties": {
                    "summary": {
                      "type": "string",
                      "description": "Concise summary."
                    },
                    "riskLevel": {
                      "type": "string",
                      "description": "Technical risk level.",
                      "enum": [
                        "LOW",
                        "MEDIUM",
                        "HIGH"
                      ]
                    }
                  },
                  "required": [
                    "summary",
                    "riskLevel"
                  ],
                  "additionalProperties": false
                }
                """);
    }

    private JsonNode schemaUnchecked() {
        try {
            return this.schema();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CodexAppServerClient client(final FakeStarter starter, final CodexAppServerProperties properties) {
        return new CodexAppServerClient(this.objectMapper, starter, properties);
    }

    private CodexAppServerProperties properties() {
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.setTurnTimeout(Duration.ofSeconds(2));
        properties.setGracefulTerminateTimeout(Duration.ofMillis(10));
        properties.setForceKillTimeout(Duration.ofMillis(100));
        return properties;
    }

    private JsonNode readRequest(final FakeCodexProcess process) throws Exception {
        return this.objectMapper.readTree(process.readRequest());
    }

    private static void assertAgentExecutionFailure(final CompletableFuture<CodexTurnResult> result, final String code) {
        assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .satisfies(throwable -> assertThat(throwable.getCause())
                        .isInstanceOfSatisfying(AgentExecutionException.class, exception -> {
                            assertThat(exception.code()).isEqualTo(code);
                            assertThat(exception.safeMessage()).isNotBlank();
                        }));
    }

    private record TurnHarness(CodexAppServerClient client, FakeCodexProcess process, CompletableFuture<CodexTurnResult> result) {
    }

    private static final class FakeStarter implements CodexAppServerProcessStarter {
        private final Queue<FakeCodexProcess> processes;
        private int starts;

        private FakeStarter(final FakeCodexProcess... processes) {
            this.processes = new ArrayDeque<>(List.of(processes));
        }

        @Override
        public StartedCodexAppServer start() {
            this.starts++;
            return new StartedCodexAppServer(this.processes.remove(), List.of("codex", "app-server", "--stdio"), Instant.now());
        }

        private int starts() {
            return this.starts;
        }
    }
}
