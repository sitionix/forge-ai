package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.RuntimeProviderStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CodexAppServerClientTest {

    private static final String MODEL_LIST = "model/list";

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

        assertThat(version.get(1, TimeUnit.SECONDS)).isEqualTo("9.9.9");
        assertThat(initialized.has("id")).isFalse();
        assertThat(initialized.path("method").asText()).isEqualTo("initialized");
        client.close();
    }

    @Test
    void initializeExtractsVersionFromUserAgentWithTrailingRuntimeMetadata() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());

        this.initialize(
                client,
                process,
                "forge-agent-probe/0.147.0 (Mac OS 26.5.2; arm64) iTerm.app/3.6.11 (forge-agent-probe; 0.0.0)",
                "0.147.0"
        );

        client.close();
    }

    @Test
    void missingUserAgentFailsInitialization() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess();
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final CompletableFuture<String> version = CompletableFuture.supplyAsync(client::version);

        final JsonNode initialize = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + initialize.path("id").asText() + "\",\"result\":{}}");

        assertThatThrownBy(() -> version.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CodexTransportException.class);
    }

    @Test
    void blankUserAgentFailsInitialization() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess();
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final CompletableFuture<String> version = CompletableFuture.supplyAsync(client::version);

        final JsonNode initialize = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + initialize.path("id").asText() + "\",\"result\":{\"userAgent\":\"  \"}}");

        assertThatThrownBy(() -> version.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CodexTransportException.class);
    }

    @Test
    void malformedUserAgentFailsInitialization() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess();
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final CompletableFuture<String> version = CompletableFuture.supplyAsync(client::version);

        final JsonNode initialize = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + initialize.path("id").asText() + "\",\"result\":{\"userAgent\":\"codex-cli 9.9.9\"}}");

        assertThatThrownBy(() -> version.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CodexTransportException.class);
    }

    @Test
    void blankUserAgentVersionTokenFailsInitialization() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess();
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final CompletableFuture<String> version = CompletableFuture.supplyAsync(client::version);

        final JsonNode initialize = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + initialize.path("id").asText() + "\",\"result\":{\"userAgent\":\"codex-cli/   metadata\"}}");

        assertThatThrownBy(() -> version.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CodexTransportException.class);
    }

    @Test
    void healthyInitializedProcessIsReusedForModelRequests() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerClient client = this.client(starter, this.properties());
        this.initialize(client, process, "codex/1", "1");

        final CompletableFuture<JsonNode> response = CompletableFuture.supplyAsync(() -> client.request(MODEL_LIST, this.objectMapper.createObjectNode()));
        final JsonNode request = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":{\"data\":[]}}");

        assertThat(response.get(1, TimeUnit.SECONDS).path("data")).isEmpty();
        assertThat(starter.starts()).isEqualTo(1);
        client.close();
    }

    @Test
    void remoteJsonRpcErrorDoesNotInvalidateHealthyInitializedProcess() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerClient client = this.client(starter, this.properties());
        this.initialize(client, process, "codex/1", "1");

        final CompletableFuture<JsonNode> failed = CompletableFuture.supplyAsync(() -> client.request(MODEL_LIST, this.objectMapper.createObjectNode()));
        final JsonNode firstRequest = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + firstRequest.path("id").asText() + "\",\"error\":{\"code\":-32000,\"message\":\"request scoped failure\"}}");

        assertThatThrownBy(() -> failed.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CodexRemoteException.class);

        final CompletableFuture<JsonNode> succeeded = CompletableFuture.supplyAsync(() -> client.request(MODEL_LIST, this.objectMapper.createObjectNode()));
        final JsonNode secondRequest = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + secondRequest.path("id").asText() + "\",\"result\":{\"data\":[]}}");

        assertThat(succeeded.get(1, TimeUnit.SECONDS).path("data")).isEmpty();
        assertThat(starter.starts()).isEqualTo(1);
        client.close();
    }

    @Test
    void runtimeAdapterDegradesOnRemoteModelListErrorAndNextDiscoveryReusesInitializedProcess() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerProperties properties = this.properties();
        final CodexAppServerClient client = this.client(starter, properties);
        final CodexRuntimeAdapter adapter = new CodexRuntimeAdapter(this.objectMapper, client, properties);

        final CompletableFuture<RuntimeProviderStatus> firstStatus = CompletableFuture.supplyAsync(() -> adapter.getModels().status());
        final JsonNode initialize = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + initialize.path("id").asText() + "\",\"result\":{\"userAgent\":\"codex/1\"}}");
        this.readRequest(process);
        final JsonNode firstModelList = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + firstModelList.path("id").asText() + "\",\"error\":{\"code\":-32000,\"message\":\"request scoped failure\"}}");

        assertThat(firstStatus.get(1, TimeUnit.SECONDS)).isEqualTo(RuntimeProviderStatus.DEGRADED);

        final CompletableFuture<RuntimeProviderStatus> secondStatus = CompletableFuture.supplyAsync(() -> adapter.getModels().status());
        final JsonNode secondModelList = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + secondModelList.path("id").asText() + "\",\"result\":{\"data\":[]}}");

        assertThat(secondStatus.get(1, TimeUnit.SECONDS)).isEqualTo(RuntimeProviderStatus.READY);
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
        this.initialize(client, first, "codex/1", "1");

        final CompletableFuture<JsonNode> timeout = CompletableFuture.supplyAsync(() -> client.request("slow", null));
        this.readRequest(first);
        assertThatThrownBy(() -> timeout.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CodexTransportException.class);

        final CompletableFuture<String> version = CompletableFuture.supplyAsync(client::version);
        final JsonNode initialize = this.readRequest(second);
        second.writeStdout("{\"id\":\"" + initialize.path("id").asText() + "\",\"result\":{\"userAgent\":\"codex/2\"}}");
        this.readRequest(second);

        assertThat(version.get(1, TimeUnit.SECONDS)).isEqualTo("2");
        assertThat(starter.starts()).isEqualTo(2);
        client.close();
    }

    @Test
    void defaultStarterLaunchesProcessInResolvedWorkingDirectory() throws Exception {
        final CodexAppServerProperties properties = this.properties();
        final Path runtimeCwd = Files.createDirectories(
                Files.createTempDirectory("forge-agent-codex-starter").resolve("runtime"));
        properties.setCommand(List.of("pwd"));
        final DefaultCodexAppServerProcessStarter starter = new DefaultCodexAppServerProcessStarter(properties);

        final StartedCodexAppServer started = starter.start(runtimeCwd);

        assertThat(Files.isDirectory(runtimeCwd)).isTrue();
        assertThat(started.process().waitFor(1, TimeUnit.SECONDS)).isTrue();
        assertThat(started.process().inputReader().readLine()).isEqualTo(runtimeCwd.toString());
    }

    @Test
    void defaultStarterRejectsMissingWorkingDirectoryWithoutCreatingIt() throws Exception {
        final CodexAppServerProperties properties = this.properties();
        properties.setCommand(List.of("pwd"));
        final Path missing = Files.createTempDirectory("forge-agent-codex-missing").resolve("absent");
        final DefaultCodexAppServerProcessStarter starter = new DefaultCodexAppServerProcessStarter(properties);

        assertThatThrownBy(() -> starter.start(missing))
                .isInstanceOf(CodexTransportException.class)
                .hasMessage("Codex app-server working directory is unavailable");
        assertThat(missing).doesNotExist();
    }

    @Test
    void noReplacementProcessStartsWhenOldProcessCleanupIsIncomplete() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, false);
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerProperties properties = this.properties();
        properties.setRequestTimeout(Duration.ofMillis(40));
        final CodexAppServerClient client = this.client(starter, properties);
        this.initialize(client, process, "codex/1", "1");

        final CompletableFuture<JsonNode> timeout = CompletableFuture.supplyAsync(() -> client.request("slow", null));
        this.readRequest(process);
        assertThatThrownBy(() -> timeout.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CodexTransportException.class);
        assertThatThrownBy(client::version).isInstanceOf(CodexTransportException.class);
        assertThat(starter.starts()).isEqualTo(1);
        process.terminateNow();
    }

    @Test
    void failedInitializeWithIncompleteCleanupRetainsOwnershipUntilOldProcessLaterExits() throws Exception {
        final FakeCodexProcess first = new FakeCodexProcess(false, false);
        final FakeCodexProcess second = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(first, second);
        final CodexAppServerProperties properties = this.properties();
        properties.setRequestTimeout(Duration.ofMillis(40));
        final CodexAppServerClient client = this.client(starter, properties);

        final CompletableFuture<String> failedInitialize = CompletableFuture.supplyAsync(client::version);
        assertThat(this.readRequest(first).path("method").asText()).isEqualTo("initialize");

        assertThatThrownBy(() -> failedInitialize.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CodexTransportException.class);
        assertThat(first.destroyed()).isTrue();
        assertThat(first.forciblyDestroyed()).isTrue();
        assertThat(first.isAlive()).isTrue();
        assertThat(starter.starts()).isEqualTo(1);

        assertThatThrownBy(client::version).isInstanceOf(CodexTransportException.class);
        assertThat(starter.starts()).isEqualTo(1);

        first.terminateNow();
        final CompletableFuture<String> recoveredVersion = CompletableFuture.supplyAsync(client::version);
        final JsonNode replacementInitialize = this.readRequest(second);
        second.writeStdout("{\"id\":\"" + replacementInitialize.path("id").asText() + "\",\"result\":{\"userAgent\":\"codex-cli/9.9.9\"}}");
        this.readRequest(second);

        assertThat(recoveredVersion.get(1, TimeUnit.SECONDS)).isEqualTo("9.9.9");
        assertThat(starter.starts()).isEqualTo(2);
        client.close();
    }

    private void initialize(final CodexAppServerClient client,
                            final FakeCodexProcess process,
                            final String userAgent,
                            final String expectedVersion) throws Exception {
        final CompletableFuture<String> call = CompletableFuture.supplyAsync(client::version);
        final JsonNode initialize = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + initialize.path("id").asText() + "\",\"result\":{\"userAgent\":\"" + userAgent + "\"}}");
        this.readRequest(process);
        assertThat(call.get(1, TimeUnit.SECONDS)).isEqualTo(expectedVersion);
    }

    private CodexAppServerClient client(final FakeStarter starter, final CodexAppServerProperties properties) {
        return new CodexAppServerClient(this.objectMapper, starter, properties, new CodexRuntimeWorkspace(properties));
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
        public StartedCodexAppServer start(final Path workingDirectory) {
            this.starts++;
            return new StartedCodexAppServer(this.processes.remove(), List.of("codex", "app-server", "--stdio"), Instant.now());
        }

        private int starts() {
            return this.starts;
        }
    }
}
