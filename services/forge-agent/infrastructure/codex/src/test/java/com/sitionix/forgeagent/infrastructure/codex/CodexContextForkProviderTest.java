package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CodexContextForkProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void forksExactlyOnceThroughPersistedTurnWithoutInvocation() throws Exception {
        try (var harness = harness("0.154.0")) {
            harness.provider.validateSupport("codex", "0.154.0");
            var result = CompletableFuture.supplyAsync(() -> harness.provider.fork("codex", "0.154.0", "source", "exact-turn"));
            var request = forkRequest(harness.process);
            assertThat(request.path("method").asText()).isEqualTo("thread/fork");
            assertThat(request.path("params")).isEqualTo(mapper.readTree("""
                    {"threadId":"source","lastTurnId":"exact-turn","ephemeral":false,"excludeTurns":true}
                    """));
            reply(harness.process, request, "{\"thread\":{\"id\":\"child-exact\"}}");
            assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo("child-exact");
            assertThat(harness.process.pendingClientRequestBytes()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"thread\":{}}", "{\"thread\":{\"id\":null}}", "{\"thread\":{\"id\":42}}", "{\"thread\":{\"id\":\" \"}}", "{\"thread\":{\"id\":\"source\"}}"})
    void malformedChildFailsWithoutRetry(String response) throws Exception {
        try (var harness = harness("0.154.0")) {
            var result = CompletableFuture.supplyAsync(() -> harness.provider.fork("codex", "0.154.0", "source", "turn"));
            var request = forkRequest(harness.process);
            reply(harness.process, request, response);
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS)).cause()
                    .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("AGENT_CONTEXT_FORK_FAILED");
            assertThat(harness.process.pendingClientRequestBytes()).isZero();
        }
    }

    @Test void providerErrorFailsWithoutRetry() throws Exception {
        try (var harness = harness("0.154.0")) {
            var result = CompletableFuture.supplyAsync(() -> harness.provider.fork("codex", "0.154.0", "source", "turn"));
            var request = forkRequest(harness.process);
            harness.process.writeStdout("{\"id\":\"" + request.path("id").asText()
                    + "\",\"error\":{\"code\":-32600,\"message\":\"fork rejected\"}}");
            assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS)).cause()
                    .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("AGENT_CONTEXT_FORK_FAILED");
            assertThat(harness.process.pendingClientRequestBytes()).isZero();
        }
    }

    @Test void timeoutFailsWithoutRetry() throws Exception {
        try (var harness = harness("0.154.0")) {
            var result = CompletableFuture.supplyAsync(() -> harness.provider.fork("codex", "0.154.0", "source", "turn"));
            assertThat(forkRequest(harness.process).path("method").asText()).isEqualTo("thread/fork");
            assertThatThrownBy(() -> result.get(4, TimeUnit.SECONDS)).cause()
                    .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("AGENT_CONTEXT_FORK_FAILED");
            assertThat(harness.process.pendingClientRequestBytes()).isZero();
        }
    }

    @Test void rejectsUnsupportedInstalledVersionBeforeFork() throws Exception {
        try (var harness = harness("0.155.0")) {
            assertThatThrownBy(() -> harness.provider.validateSupport("codex", "0.154.0"))
                    .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("AGENT_CONTEXT_FORK_UNSUPPORTED");
            assertThat(harness.process.pendingClientRequestBytes()).isZero();
        }
    }

    @Test void rejectsUnsupportedPersistedProviderAndVersion() throws Exception {
        try (var harness = harness("0.154.0")) {
            assertThatThrownBy(() -> harness.provider.validateSupport("other", "0.154.0"))
                    .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("AGENT_CONTEXT_FORK_UNSUPPORTED");
            assertThatThrownBy(() -> harness.provider.validateSupport("codex", "0.153.0"))
                    .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("AGENT_CONTEXT_FORK_UNSUPPORTED");
            assertThat(harness.process.pendingClientRequestBytes()).isZero();
        }
    }

    private Harness harness(String version) throws Exception {
        var initialProcess = new FakeCodexProcess(false, true);
        var process = new FakeCodexProcess(false, true);
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        var properties = new CodexAppServerProperties();
        properties.setRuntimeCwd(System.getProperty("java.io.tmpdir"));
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.setGracefulTerminateTimeout(Duration.ofMillis(20));
        properties.setForceKillTimeout(Duration.ofMillis(20));
        var client = new CodexAppServerClient(mapper,
                path -> new StartedCodexAppServer(starts.getAndIncrement() == 0 ? initialProcess : process, List.of("codex", "app-server"), Instant.now()),
                properties, new CodexRuntimeWorkspace(properties));
        var initialized = CompletableFuture.supplyAsync(client::version);
        var request = mapper.readTree(initialProcess.readRequest());
        reply(initialProcess, request, "{\"userAgent\":\"codex-cli/" + version + "\"}");
        assertThat(mapper.readTree(initialProcess.readRequest()).path("method").asText()).isEqualTo("initialized");
        assertThat(initialized.get(2, TimeUnit.SECONDS)).isEqualTo(version);
        return new Harness(process, client, new CodexContextForkProvider(client));
    }
    private JsonNode forkRequest(FakeCodexProcess process) throws Exception {
        var initialize = mapper.readTree(process.readRequest());
        assertThat(initialize.path("method").asText()).isEqualTo("initialize");
        reply(process, initialize, "{\"userAgent\":\"codex-cli/0.154.0\"}");
        assertThat(mapper.readTree(process.readRequest()).path("method").asText()).isEqualTo("initialized");
        return mapper.readTree(process.readRequest());
    }
    private void reply(FakeCodexProcess process, JsonNode request, String result) {
        process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":" + result + "}");
    }
    private record Harness(FakeCodexProcess process, CodexAppServerClient client, CodexContextForkProvider provider) implements AutoCloseable {
        public void close() { client.close(); }
    }
}
