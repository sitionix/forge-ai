package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.ProviderTurnRecoveryResult;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryState;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryTerminalOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CodexRecoveryProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exactCompletedTurnIsTerminalSucceeded() throws Exception {
        this.assertSinglePageClassification("completed", ProviderTurnRecoveryState.TERMINAL,
                ProviderTurnRecoveryTerminalOutcome.SUCCEEDED);
    }

    @Test
    void exactFailedTurnIsTerminalFailed() throws Exception {
        this.assertSinglePageClassification("failed", ProviderTurnRecoveryState.TERMINAL,
                ProviderTurnRecoveryTerminalOutcome.FAILED);
    }

    @Test
    void exactInterruptedTurnIsTerminalCancelled() throws Exception {
        this.assertSinglePageClassification("interrupted", ProviderTurnRecoveryState.TERMINAL,
                ProviderTurnRecoveryTerminalOutcome.CANCELLED);
    }

    @Test
    void exactInProgressTurnIsActive() throws Exception {
        this.assertSinglePageClassification("inProgress", ProviderTurnRecoveryState.ACTIVE,
                ProviderTurnRecoveryTerminalOutcome.UNKNOWN);
    }

    @Test
    void exactTurnCanBeLocatedOnSecondPage() throws Exception {
        final Harness harness = this.harness(Duration.ofSeconds(1));
        final CompletableFuture<ProviderTurnRecoveryResult> result = this.inspect(harness, "thread-target", "turn-target");

        final JsonNode first = this.readRequest(harness.process());
        this.assertTurnsListRequest(first, "thread-target", null);
        this.reply(harness.process(), first,
                "{\"data\":[{\"id\":\"turn-other\",\"items\":[],\"status\":\"completed\"}],\"nextCursor\":\"cursor-2\"}");
        final JsonNode second = this.readRequest(harness.process());
        this.assertTurnsListRequest(second, "thread-target", "cursor-2");
        this.reply(harness.process(), second,
                "{\"data\":[{\"id\":\"turn-target\",\"items\":[],\"status\":\"completed\"}],\"nextCursor\":null}");

        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo(ProviderTurnRecoveryResult.terminal(
                ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "Codex turn status completed"));
        harness.transport().close();
    }

    @Test
    void absentExactTurnIsUnknown() throws Exception {
        final ProviderTurnRecoveryResult result = this.singleResponse(
                "thread-target", "turn-target",
                "{\"data\":[{\"id\":\"turn-other\",\"items\":[],\"status\":\"completed\"}],\"nextCursor\":null}");

        assertThat(result.state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
    }

    @Test
    void blankRequestedOrResponseIdentityIsUnknown() throws Exception {
        final Harness blankRequestHarness = this.harness(Duration.ofSeconds(1));
        assertThat(blankRequestHarness.protocol().inspectTurn(
                blankRequestHarness.transport(), " ", "turn-target", Duration.ofSeconds(1)).state())
                .isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        assertThat(blankRequestHarness.process().pendingClientRequestBytes()).isZero();
        blankRequestHarness.transport().close();

        final ProviderTurnRecoveryResult blankResponse = this.singleResponse(
                "thread-target", "turn-target",
                "{\"data\":[{\"id\":\" \",\"items\":[],\"status\":\"completed\"}],\"nextCursor\":null}");
        assertThat(blankResponse.state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
    }

    @Test
    void duplicateExactIdentityIsUnknown() throws Exception {
        final ProviderTurnRecoveryResult result = this.singleResponse(
                "thread-target", "turn-target",
                "{\"data\":[{\"id\":\"turn-target\",\"items\":[],\"status\":\"completed\"},"
                        + "{\"id\":\"turn-target\",\"items\":[],\"status\":\"completed\"}],\"nextCursor\":null}");

        assertThat(result.state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
    }

    @Test
    void repeatedCursorIsUnknownWithoutUnboundedRequests() throws Exception {
        final Harness harness = this.harness(Duration.ofSeconds(1));
        final CompletableFuture<ProviderTurnRecoveryResult> result = this.inspect(harness, "thread-target", "turn-target");

        final JsonNode first = this.readRequest(harness.process());
        this.reply(harness.process(), first, "{\"data\":[],\"nextCursor\":\"same\"}");
        final JsonNode second = this.readRequest(harness.process());
        this.reply(harness.process(), second, "{\"data\":[],\"nextCursor\":\"same\"}");

        assertThat(result.get(1, TimeUnit.SECONDS).state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        assertThat(harness.process().pendingClientRequestBytes()).isZero();
        harness.transport().close();
    }

    @Test
    void paginationStopsAtOneHundredPages() throws Exception {
        final Harness harness = this.harness(Duration.ofSeconds(1));
        final CompletableFuture<ProviderTurnRecoveryResult> result = this.inspect(harness, "thread-target", "turn-target");

        for (int page = 0; page < 100; page++) {
            final JsonNode request = this.readRequest(harness.process());
            this.reply(harness.process(), request,
                    "{\"data\":[],\"nextCursor\":\"cursor-" + page + "\"}");
        }

        assertThat(result.get(1, TimeUnit.SECONDS).state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        assertThat(harness.process().pendingClientRequestBytes()).isZero();
        harness.transport().close();
    }

    @Test
    void malformedDataAndUnknownStatusAreUnknown() throws Exception {
        assertThat(this.singleResponse("thread-target", "turn-target",
                "{\"data\":{},\"nextCursor\":null}").state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        assertThat(this.singleResponse("thread-target", "turn-target",
                "{\"data\":[{\"id\":\"turn-target\",\"items\":[],\"status\":\"mystery\"}],\"nextCursor\":null}")
                .state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
    }

    @Test
    void exactTurnWithMissingOrWrongTypeRequiredFieldsIsUnknown() throws Exception {
        for (final String response : List.of(
                "{\"data\":[{\"id\":\"turn-target\",\"status\":\"completed\"}],\"nextCursor\":null}",
                "{\"data\":[{\"id\":\"turn-target\",\"items\":{},\"status\":\"completed\"}],\"nextCursor\":null}",
                "{\"data\":[{\"id\":\"turn-target\",\"items\":[]}],\"nextCursor\":null}",
                "{\"data\":[{\"id\":\"turn-target\",\"items\":[],\"status\":{}}],\"nextCursor\":null}"
        )) {
            assertThat(this.singleResponse("thread-target", "turn-target", response).state())
                    .as(response)
                    .isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        }
    }

    @Test
    void malformedNonTargetTurnMakesExactTargetResultUnknown() throws Exception {
        for (final String malformedTurn : List.of(
                "{\"id\":\"turn-other\",\"status\":\"completed\"}",
                "{\"id\":\"turn-other\",\"items\":{},\"status\":\"completed\"}",
                "{\"id\":\"turn-other\",\"items\":[]}",
                "{\"id\":\"turn-other\",\"items\":[],\"status\":{}}",
                "{\"id\":\"turn-other\",\"items\":[],\"status\":\"mystery\"}"
        )) {
            final String response = "{\"data\":[" + malformedTurn
                    + ",{\"id\":\"turn-target\",\"items\":[],\"status\":\"completed\"}],\"nextCursor\":null}";
            assertThat(this.singleResponse("thread-target", "turn-target", response).state())
                    .as(response)
                    .isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        }
    }

    @Test
    void unknownThreadRemoteErrorIsUnknown() throws Exception {
        final Harness harness = this.harness(Duration.ofSeconds(1));
        final CompletableFuture<ProviderTurnRecoveryResult> result = this.inspect(harness, "missing-thread", "turn-target");
        final JsonNode request = this.readRequest(harness.process());
        harness.process().writeStdout("{\"id\":\"" + request.path("id").asText()
                + "\",\"error\":{\"code\":-32600,\"message\":\"no rollout found for thread id missing-thread\"}}");

        assertThat(result.get(1, TimeUnit.SECONDS).state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        harness.transport().close();
    }

    @Test
    void requestTimeoutIsUnknown() throws Exception {
        final Harness harness = this.harness(Duration.ofMillis(30));

        final ProviderTurnRecoveryResult result = harness.protocol().inspectTurn(
                harness.transport(), "thread-target", "turn-target", Duration.ofMillis(30));

        assertThat(result.state()).isEqualTo(ProviderTurnRecoveryState.UNKNOWN);
        harness.transport().close();
    }

    private void assertSinglePageClassification(final String status, final ProviderTurnRecoveryState state,
                                                final ProviderTurnRecoveryTerminalOutcome outcome) throws Exception {
        final Harness harness = this.harness(Duration.ofSeconds(1));
        final CompletableFuture<ProviderTurnRecoveryResult> result = this.inspect(harness, "thread-target", "turn-target");
        final JsonNode request = this.readRequest(harness.process());
        this.assertTurnsListRequest(request, "thread-target", null);
        this.reply(harness.process(), request,
                "{\"data\":[{\"id\":\"turn-target\",\"items\":[],\"status\":\"" + status
                        + "\"}],\"nextCursor\":null}");

        assertThat(result.get(1, TimeUnit.SECONDS).state()).isEqualTo(state);
        assertThat(result.get(1, TimeUnit.SECONDS).terminalOutcome()).isEqualTo(outcome);
        harness.transport().close();
    }

    private ProviderTurnRecoveryResult singleResponse(final String threadId, final String turnId,
                                                      final String response) throws Exception {
        final Harness harness = this.harness(Duration.ofSeconds(1));
        final CompletableFuture<ProviderTurnRecoveryResult> result = this.inspect(harness, threadId, turnId);
        final JsonNode request = this.readRequest(harness.process());
        this.reply(harness.process(), request, response);
        final ProviderTurnRecoveryResult recovered = result.get(1, TimeUnit.SECONDS);
        harness.transport().close();
        return recovered;
    }

    private CompletableFuture<ProviderTurnRecoveryResult> inspect(final Harness harness, final String threadId,
                                                                  final String turnId) {
        return CompletableFuture.supplyAsync(() -> harness.protocol().inspectTurn(
                harness.transport(), threadId, turnId, Duration.ofSeconds(1)));
    }

    private void assertTurnsListRequest(final JsonNode request, final String threadId, final String cursor)
            throws Exception {
        assertThat(request.path("method").asText()).isEqualTo("thread/turns/list");
        final String expected = cursor == null
                ? "{\"threadId\":\"" + threadId + "\",\"limit\":100,\"sortDirection\":\"desc\",\"itemsView\":\"notLoaded\"}"
                : "{\"threadId\":\"" + threadId + "\",\"cursor\":\"" + cursor
                    + "\",\"limit\":100,\"sortDirection\":\"desc\",\"itemsView\":\"notLoaded\"}";
        assertThat(request.path("params")).isEqualTo(this.objectMapper.readTree(expected));
        assertThat(request.path("params").has("includeTurns")).isFalse();
    }

    private Harness harness(final Duration requestTimeout) {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setRequestTimeout(requestTimeout);
        properties.setGracefulTerminateTimeout(Duration.ofMillis(20));
        properties.setForceKillTimeout(Duration.ofMillis(20));
        final CodexJsonRpcTransport transport = new CodexJsonRpcTransport(
                this.objectMapper,
                new StartedCodexAppServer(process, List.of("codex", "app-server", "--stdio"), Instant.now()),
                properties
        );
        return new Harness(process, transport, new CodexRecoveryProtocol(this.objectMapper));
    }

    private JsonNode readRequest(final FakeCodexProcess process) throws Exception {
        return this.objectMapper.readTree(process.readRequest());
    }

    private void reply(final FakeCodexProcess process, final JsonNode request, final String result) {
        process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":" + result + "}");
    }

    private record Harness(FakeCodexProcess process, CodexJsonRpcTransport transport,
                           CodexRecoveryProtocol protocol) {
    }
}
