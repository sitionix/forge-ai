package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CodexAppServerClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void initializeRequestExtractsVersionAndSendsInitializedNotification() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final CompletableFuture<String> version = CompletableFuture.supplyAsync(client::version);

        final JsonNode initialize = this.readRequest(process);
        assertThat(initialize.path("method").asText()).isEqualTo("initialize");
        assertThat(initialize.path("params").path("clientInfo").path("name").asText()).isEqualTo("forge_agent");
        process.writeStdout("{\"id\":\"" + initialize.path("id").asText() + "\",\"result\":{\"userAgent\":\"codex-cli/9.9.9\"}}");
        final JsonNode initialized = this.readRequest(process);

        assertThat(version.get(1, TimeUnit.SECONDS)).isEqualTo("codex-cli/9.9.9");
        assertThat(initialized.has("id")).isFalse();
        assertThat(initialized.path("method").asText()).isEqualTo("initialized");
        client.close();
    }

    @Test
    void healthyInitializedProcessIsReusedForModelRequests() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerClient client = this.client(starter, this.properties());
        this.initialize(client, process, "codex/1");

        final CompletableFuture<JsonNode> response = CompletableFuture.supplyAsync(() -> client.request("model/list", this.objectMapper.createObjectNode()));
        final JsonNode request = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":{\"data\":[]}}");

        assertThat(response.get(1, TimeUnit.SECONDS).path("data")).isEmpty();
        assertThat(starter.starts()).isEqualTo(1);
        client.close();
    }

    @Test
    void requestAfterTimeoutStartsFreshProcessAfterCleanup() throws Exception {
        final FakeCodexProcess first = new FakeCodexProcess(true, true);
        final FakeCodexProcess second = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(first, second);
        final CodexAppServerProperties properties = this.properties();
        properties.setRequestTimeout(Duration.ofMillis(40));
        final CodexAppServerClient client = this.client(starter, properties);
        this.initialize(client, first, "codex/1");

        final CompletableFuture<JsonNode> timeout = CompletableFuture.supplyAsync(() -> client.request("slow", null));
        this.readRequest(first);
        assertThatThrownBy(() -> timeout.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CodexTransportException.class);

        final CompletableFuture<String> version = CompletableFuture.supplyAsync(client::version);
        final JsonNode initialize = this.readRequest(second);
        second.writeStdout("{\"id\":\"" + initialize.path("id").asText() + "\",\"result\":{\"userAgent\":\"codex/2\"}}");
        this.readRequest(second);

        assertThat(version.get(1, TimeUnit.SECONDS)).isEqualTo("codex/2");
        assertThat(starter.starts()).isEqualTo(2);
        client.close();
    }

    @Test
    void noReplacementProcessStartsWhenOldProcessCleanupIsIncomplete() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, false);
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerProperties properties = this.properties();
        properties.setRequestTimeout(Duration.ofMillis(40));
        final CodexAppServerClient client = this.client(starter, properties);
        this.initialize(client, process, "codex/1");

        final CompletableFuture<JsonNode> timeout = CompletableFuture.supplyAsync(() -> client.request("slow", null));
        this.readRequest(process);
        assertThatThrownBy(() -> timeout.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CodexTransportException.class);
        assertThatThrownBy(client::version).isInstanceOf(CodexTransportException.class);
        assertThat(starter.starts()).isEqualTo(1);
        process.terminateNow();
    }

    private void initialize(final CodexAppServerClient client, final FakeCodexProcess process, final String version) throws Exception {
        final CompletableFuture<String> call = CompletableFuture.supplyAsync(client::version);
        final JsonNode initialize = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + initialize.path("id").asText() + "\",\"result\":{\"userAgent\":\"" + version + "\"}}");
        this.readRequest(process);
        assertThat(call.get(1, TimeUnit.SECONDS)).isEqualTo(version);
    }

    private CodexAppServerClient client(final FakeStarter starter, final CodexAppServerProperties properties) {
        return new CodexAppServerClient(this.objectMapper, starter, properties);
    }

    private CodexAppServerProperties properties() {
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.setGracefulTerminateTimeout(Duration.ofMillis(10));
        properties.setForceKillTimeout(Duration.ofMillis(100));
        return properties;
    }

    private JsonNode readRequest(final FakeCodexProcess process) throws Exception {
        return this.objectMapper.readTree(process.readRequest());
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
