package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CodexAppServerTurnClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executeTurnSendsExactThreadAndTurnProtocolWithNativeOutputSchema() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final JsonNode schema = this.schema();
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Analyze auth.", "Instructions.", "gpt-5.6-luna", "high", schema, this.workspace())
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        assertThat(threadStart.path("method").asText()).isEqualTo("thread/start");
        assertThat(threadStart.path("params").path("model").asText()).isEqualTo("gpt-5.6-luna");
        assertThat(threadStart.path("params").path("developerInstructions").asText()).isEqualTo("Instructions.");
        assertThat(threadStart.path("params").has("baseInstructions")).isFalse();
        assertThat(threadStart.path("params").path("approvalPolicy").asText()).isEqualTo("never");
        assertThat(threadStart.path("params").path("sandbox").asText()).isEqualTo("workspace-write");
        assertThat(threadStart.path("params").path("ephemeral").asBoolean()).isTrue();
        assertThat(threadStart.path("params").path("config"))
                .isEqualTo(this.objectMapper.readTree("""
                        {
                          "features": {
                            "shell_tool": true
                          },
                          "agents": {
                            "enabled": false
                          }
                        }
                        """));
        final Path neutralCwd = Path.of(threadStart.path("params").path("cwd").asText());
        assertThat(neutralCwd.getFileName().toString()).isEqualTo("forge-agent-codex-runtime");
        assertThat(Files.isDirectory(neutralCwd)).isTrue();
        assertThat(threadStart.path("params").path("runtimeWorkspaceRoots"))
                .isEqualTo(this.objectMapper.readTree("[\"" + neutralCwd.toAbsolutePath().normalize() + "\"]"));
        this.replyThread(process, threadStart, "thread-1");

        final JsonNode turnStart = this.readRequest(process);
        assertThat(turnStart.path("method").asText()).isEqualTo("turn/start");
        assertThat(turnStart.path("params").path("threadId").asText()).isEqualTo("thread-1");
        assertThat(turnStart.path("params").path("input"))
                .isEqualTo(this.objectMapper.readTree("[{\"type\":\"text\",\"text\":\"Analyze auth.\",\"text_elements\":[]}]"));
        assertThat(turnStart.path("params").path("model").asText()).isEqualTo("gpt-5.6-luna");
        assertThat(turnStart.path("params").path("effort").asText()).isEqualTo("high");
        assertThat(turnStart.path("params").path("outputSchema")).isEqualTo(schema);
        this.replyTurn(process, turnStart, "turn-1");
        this.awaitActiveTurn(client);
        this.complete(process, "thread-1", "turn-1", "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");

        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");
        assertThat(client.activeTurnCountForTesting()).isZero();
        client.close();
    }

    @Test
    void nullEffortIsOmittedFromTurnStart() throws Exception {
        final TurnHarness harness = this.startedTurn(null);

        assertThat(harness.turnStart().path("params").has("effort")).isFalse();
        this.complete(harness.process(), "thread-1", "turn-1", "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");
        assertThat(harness.result().get(1, TimeUnit.SECONDS)).contains("OK");
        harness.client().close();
    }

    @Test
    void configuredRuntimeCwdOverridesNeutralDefault() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerProperties properties = this.properties();
        final Path configuredCwd = Files.createTempDirectory("forge-agent-codex-configured-cwd");
        properties.setRuntimeCwd(configuredCwd.toString());
        final CodexAppServerClient client = this.client(new FakeStarter(process), properties);
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Analyze auth.", "Instructions.", "gpt-5.6-luna", null, this.schemaUnchecked(), this.workspace(configuredCwd))
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        assertThat(threadStart.path("params").path("cwd").asText()).isEqualTo(configuredCwd.toAbsolutePath().normalize().toString());
        this.replyThread(process, threadStart, "thread-1");
        final JsonNode turnStart = this.readRequest(process);
        this.replyTurn(process, turnStart, "turn-1");
        this.complete(process, "thread-1", "turn-1", "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");

        assertThat(result.get(1, TimeUnit.SECONDS)).contains("OK");
        client.close();
    }

    @Test
    void configuredRuntimeCwdScopesWorkspaceWriteRoot() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerProperties properties = this.properties();
        final Path configuredCwd = Files.createTempDirectory("forge-agent-codex-file-work");
        properties.setRuntimeCwd(configuredCwd.toString());
        final CodexAppServerClient client = this.client(new FakeStarter(process), properties);
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Create a file.", "Instructions.", "gpt-5.6-spark", null, this.schemaUnchecked(), this.workspace(configuredCwd))
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        assertThat(threadStart.path("params").path("sandbox").asText()).isEqualTo("workspace-write");
        assertThat(threadStart.path("params").path("cwd").asText()).isEqualTo(configuredCwd.toAbsolutePath().normalize().toString());
        assertThat(threadStart.path("params").path("runtimeWorkspaceRoots"))
                .isEqualTo(this.objectMapper.readTree("[\"" + configuredCwd.toAbsolutePath().normalize() + "\"]"));
        assertThat(threadStart.path("params").path("config").path("features").path("shell_tool").asBoolean()).isTrue();
        this.replyThread(process, threadStart, "thread-1");
        final JsonNode turnStart = this.readRequest(process);
        this.replyTurn(process, turnStart, "turn-1");
        this.complete(process, "thread-1", "turn-1", "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");

        assertThat(result.get(1, TimeUnit.SECONDS)).contains("OK");
        client.close();
    }

    @Test
    void latestFinalAnswerWinsOverCommentary() throws Exception {
        final TurnHarness harness = this.startedTurn();

        this.agentMessage(harness.process(), "thread-1", "turn-1", "commentary", "{\"summary\":\"Commentary\",\"riskLevel\":\"HIGH\"}");
        this.agentMessage(harness.process(), "thread-1", "turn-1", "final_answer", "{\"summary\":\"Final\",\"riskLevel\":\"LOW\"}");
        this.turnCompleted(harness.process(), "thread-1", "turn-1", "completed");

        assertThat(harness.result().get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"Final\",\"riskLevel\":\"LOW\"}");
        harness.client().close();
    }

    @Test
    void laterCommentaryDoesNotReplaceFinalAnswer() throws Exception {
        final TurnHarness harness = this.startedTurn();

        this.agentMessage(harness.process(), "thread-1", "turn-1", "final_answer", "{\"summary\":\"Final\",\"riskLevel\":\"LOW\"}");
        this.agentMessage(harness.process(), "thread-1", "turn-1", "commentary", "{\"summary\":\"Commentary\",\"riskLevel\":\"HIGH\"}");
        this.turnCompleted(harness.process(), "thread-1", "turn-1", "completed");

        assertThat(harness.result().get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"Final\",\"riskLevel\":\"LOW\"}");
        harness.client().close();
    }

    @Test
    void nullPhaseAgentMessageIsCompatibilityFallback() throws Exception {
        final TurnHarness harness = this.startedTurn();

        this.agentMessage(harness.process(), "thread-1", "turn-1", null, "{\"summary\":\"Fallback\",\"riskLevel\":\"MEDIUM\"}");
        this.turnCompleted(harness.process(), "thread-1", "turn-1", "completed");

        assertThat(harness.result().get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"Fallback\",\"riskLevel\":\"MEDIUM\"}");
        harness.client().close();
    }

    @Test
    void commentaryOnlyCompletedTurnFailsSafely() throws Exception {
        final TurnHarness harness = this.startedTurn();

        this.agentMessage(harness.process(), "thread-1", "turn-1", "commentary", "{\"summary\":\"Commentary\",\"riskLevel\":\"HIGH\"}");
        this.turnCompleted(harness.process(), "thread-1", "turn-1", "completed");

        assertExecutionFailure(harness.result());
        harness.client().close();
    }

    @Test
    void failedAndInterruptedTurnsFailSafely() throws Exception {
        final TurnHarness failed = this.startedTurn();
        this.turnCompleted(failed.process(), "thread-1", "turn-1", "failed");
        assertExecutionFailure(failed.result());
        failed.client().close();

        final TurnHarness interrupted = this.startedTurn();
        this.turnCompleted(interrupted.process(), "thread-1", "turn-1", "interrupted");
        assertExecutionFailure(interrupted.result());
        interrupted.client().close();
    }

    @Test
    void failedTurnPreservesProviderErrorMessage() throws Exception {
        final TurnHarness harness = this.startedTurn();

        this.turnCompleted(harness.process(), "thread-1", "turn-1", "failed", "Test provider failure.");

        assertExecutionFailure(harness.result(), "Test provider failure.");
        harness.client().close();
    }

    @Test
    void failedTurnWithoutUsefulProviderErrorUsesGenericMessage() throws Exception {
        final TurnHarness harness = this.startedTurn();

        this.turnCompleted(harness.process(), "thread-1", "turn-1", "failed", " ");

        assertExecutionFailure(harness.result(), "Codex execution failed.");
        harness.client().close();
    }

    @Test
    void turnStartJsonRpcErrorPreservesRemoteMessage() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Analyze auth.", "Instructions.", "model-a", null, this.schemaUnchecked(), this.workspace())
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        this.replyThread(process, threadStart, "thread-1");
        final JsonNode turnStart = this.readRequest(process);
        process.writeStdout("{\"id\":\"" + turnStart.path("id").asText()
                + "\",\"error\":{\"code\":-32602,\"message\":\"Invalid schema for response format.\"}}");

        assertExecutionFailure(result, "Invalid schema for response format.");
        client.close();
    }

    @Test
    void completedWithoutAgentMessageFailsSafely() throws Exception {
        final TurnHarness harness = this.startedTurn();

        this.turnCompleted(harness.process(), "thread-1", "turn-1", "completed");

        assertExecutionFailure(harness.result());
        harness.client().close();
    }

    @Test
    void mcpToolCallGenerationItemFailsExactTurnAndInterrupts() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout(this.itemStartedNotification("thread-1", "turn-1", "mcpToolCall"));
        this.assertInterrupt(harness.process(), "thread-1", "turn-1");

        assertExecutionFailure(harness.result());
        this.complete(harness.process(), "thread-1", "turn-1", "{\"summary\":\"Late\",\"riskLevel\":\"LOW\"}");
        assertThat(harness.client().activeTurnCountForTesting()).isZero();
        harness.client().close();
    }

    @Test
    void unknownGenerationItemFailsExactTurnAndInterrupts() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout(this.itemStartedNotification("thread-1", "turn-1", "unknownNewItem"));
        this.assertInterrupt(harness.process(), "thread-1", "turn-1");

        assertExecutionFailure(harness.result());
        harness.client().close();
    }

    @Test
    void fileOperationGenerationItemsAreAllowedByDefault() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout(this.itemStartedNotification("thread-1", "turn-1", "commandExecution"));
        harness.process().writeStdout(this.itemStartedNotification("thread-1", "turn-1", "fileChange"));
        this.complete(harness.process(), "thread-1", "turn-1", "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");

        assertThat(harness.result().get(1, TimeUnit.SECONDS)).contains("OK");
        harness.client().close();
    }

    @Test
    void normalSafeGenerationItemsRemainAllowed() throws Exception {
        final TurnHarness harness = this.startedTurn();

        for (final String itemType : List.of("userMessage", "reasoning", "plan", "contextCompaction")) {
            harness.process().writeStdout(this.itemStartedNotification("thread-1", "turn-1", itemType));
        }
        this.complete(harness.process(), "thread-1", "turn-1", "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");

        assertThat(harness.result().get(1, TimeUnit.SECONDS)).contains("OK");
        harness.client().close();
    }

    @Test
    void commandApprovalRequestIsDeclinedAndFailsOwningTurn() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout("{\"id\":\"approval-1\",\"method\":\"item/commandExecution/requestApproval\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\"}}");
        final JsonNode first = this.readRequest(harness.process());
        final JsonNode second = this.readRequest(harness.process());
        final JsonNode approvalResponse = this.approvalResponse(first) ? first : second;
        final JsonNode interrupt = "turn/interrupt".equals(first.path("method").asText()) ? first : second;

        assertThat(approvalResponse.path("id").asText()).isEqualTo("approval-1");
        assertThat(approvalResponse.path("result").path("decision").asText()).isEqualTo("decline");
        this.assertInterrupt(interrupt, "thread-1", "turn-1");
        harness.process().writeStdout("{\"id\":\"" + interrupt.path("id").asText() + "\",\"result\":{}}");
        assertExecutionFailure(harness.result());
        harness.client().close();
    }

    @Test
    void lateScopedServerRequestForRemovedTurnDoesNotFailUnrelatedActiveTurn() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerClient client = this.client(starter, this.properties());
        final JsonNode schema = this.schema();
        final CompletableFuture<String> a = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt A", "Instructions.", "model-b", null, schema, this.workspace())
        ));
        final CompletableFuture<String> b = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt B", "Instructions.", "model-c", null, schema, this.workspace())
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

        process.writeStdout(this.itemStartedNotification("thread-b", "turn-b", "mcpToolCall"));
        this.assertInterrupt(process, "thread-b", "turn-b");
        assertExecutionFailure(a);
        assertThat(client.activeTurnCountForTesting()).isEqualTo(1);

        process.writeStdout("{\"id\":\"approval-late\",\"method\":\"item/fileChange/requestApproval\",\"params\":{\"threadId\":\"thread-b\",\"turnId\":\"turn-b\"}}");
        final JsonNode lateApprovalResponse = this.readRequest(process);
        assertThat(lateApprovalResponse.path("result").path("decision").asText()).isEqualTo("decline");
        process.writeStdout("{\"id\":\"unknown-late\",\"method\":\"item/unsupported/requestApproval\",\"params\":{\"threadId\":\"thread-b\",\"turnId\":\"turn-b\"}}");
        final JsonNode lateUnknownResponse = this.readRequest(process);
        assertThat(lateUnknownResponse.path("error").path("code").asInt()).isEqualTo(-32601);

        this.complete(process, "thread-c", "turn-c", "{\"summary\":\"B\",\"riskLevel\":\"LOW\"}");

        assertThat(b.get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"B\",\"riskLevel\":\"LOW\"}");
        assertThat(client.activeTurnCountForTesting()).isZero();
        assertThat(starter.starts()).isEqualTo(1);
        client.close();
    }

    @Test
    void transportFailureFailsActiveTurnWaiter() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().closeStdout();

        assertExecutionFailure(harness.result());
    }

    @Test
    void earlyNotificationsBeforeTurnStartResponseCompleteSuccessfully() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Race.", "Instructions.", "model-a", null, this.schemaUnchecked(), this.workspace())
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        this.replyThread(process, threadStart, "thread-1");
        final JsonNode turnStart = this.readRequest(process);
        this.complete(process, "thread-1", "turn-1", "{\"summary\":\"Early\",\"riskLevel\":\"LOW\"}");
        this.replyTurn(process, turnStart, "turn-1");

        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"Early\",\"riskLevel\":\"LOW\"}");
        client.close();
    }

    @Test
    void turnTimeoutInterruptsAndFailsSafely() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerProperties properties = this.properties();
        properties.setTurnTimeout(Duration.ofMillis(40));
        final CodexAppServerClient client = this.client(new FakeStarter(process), properties);
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Slow.", "Instructions.", "model-a", null, this.schemaUnchecked(), this.workspace())
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        this.replyThread(process, threadStart, "thread-1");
        final JsonNode turnStart = this.readRequest(process);
        this.replyTurn(process, turnStart, "turn-1");
        this.assertInterrupt(process, "thread-1", "turn-1");

        assertExecutionFailure(result);
        this.turnCompleted(process, "thread-1", "turn-1", "interrupted");
        assertThat(client.activeTurnCountForTesting()).isZero();
        client.close();
    }

    @Test
    void javaThreadInterruptionInterruptsProviderTurnAndRestoresInterruptFlag() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final AtomicReference<Thread> runner = new AtomicReference<>();
        final CompletableFuture<Boolean> failedWithInterruptFlag = new CompletableFuture<>();
        final Thread executionThread = Thread.ofVirtual().start(() -> {
            runner.set(Thread.currentThread());
            try {
                client.execute(new CodexTurnRequest("Interrupt.", "Instructions.", "model-a", null, this.schemaUnchecked(), this.workspace()));
                failedWithInterruptFlag.complete(false);
            } catch (final RuntimeException exception) {
                failedWithInterruptFlag.complete(Thread.currentThread().isInterrupted());
            }
        });

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        this.replyThread(process, threadStart, "thread-1");
        final JsonNode turnStart = this.readRequest(process);
        this.replyTurn(process, turnStart, "turn-1");
        this.awaitActiveTurn(client);
        runner.get().interrupt();
        this.assertInterrupt(process, "thread-1", "turn-1");

        assertThat(failedWithInterruptFlag.get(1, TimeUnit.SECONDS)).isTrue();
        executionThread.join(TimeUnit.SECONDS.toMillis(1));
        assertThat(client.activeTurnCountForTesting()).isZero();
        client.close();
    }

    @Test
    void concurrentTurnsShareOneProcessAndReceiveOnlyTheirOwnOutput() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerClient client = this.client(starter, this.properties());
        final JsonNode schema = this.schema();
        final CompletableFuture<String> b = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt B", "Instructions.", "model-b", null, schema, this.workspace())
        ));
        final CompletableFuture<String> c = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt C", "Instructions.", "model-c", null, schema, this.workspace())
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

        assertThat(b.get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"B\",\"riskLevel\":\"HIGH\"}");
        assertThat(c.get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"C\",\"riskLevel\":\"LOW\"}");
        assertThat(starter.starts()).isEqualTo(1);
        client.close();
    }

    @Test
    void concurrentTurnsWithEarlyNotificationsRemainIsolated() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerClient client = this.client(starter, this.properties());
        final JsonNode schema = this.schema();
        final CompletableFuture<String> b = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt B", "Instructions.", "model-b", null, schema, this.workspace())
        ));
        final CompletableFuture<String> c = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt C", "Instructions.", "model-c", null, schema, this.workspace())
        ));

        this.initialize(process);
        final JsonNode threadOne = this.readRequest(process);
        final JsonNode threadTwo = this.readRequest(process);
        this.replyThread(process, threadOne);
        this.replyThread(process, threadTwo);
        final JsonNode turnOne = this.readRequest(process);
        final JsonNode turnTwo = this.readRequest(process);
        this.complete(process, "thread-b", "turn-b", "{\"summary\":\"B early\",\"riskLevel\":\"LOW\"}");
        this.complete(process, "thread-c", "turn-c", "{\"summary\":\"C early\",\"riskLevel\":\"HIGH\"}");
        this.replyTurn(process, turnTwo);
        this.replyTurn(process, turnOne);

        assertThat(b.get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"B early\",\"riskLevel\":\"LOW\"}");
        assertThat(c.get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"C early\",\"riskLevel\":\"HIGH\"}");
        assertThat(client.activeTurnCountForTesting()).isZero();
        assertThat(starter.starts()).isEqualTo(1);
        client.close();
    }

    @Test
    void sharedProcessUsesImmutableWorkspaceForEachThreadStart() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerClient client = this.client(starter, this.properties());
        final Path project = Files.createTempDirectory("forge-agent-project-workspace");
        final Path repositoryA = Files.createDirectories(project.resolve("backend"));
        final Path repositoryB = Files.createDirectories(project.resolve("frontend"));
        final List<ExecutionWorkspace> workspaces = List.of(
                new ExecutionWorkspace(repositoryA, List.of(repositoryA)),
                new ExecutionWorkspace(repositoryB, List.of(repositoryB)),
                new ExecutionWorkspace(project, List.of(repositoryA, repositoryB))
        );

        for (int index = 0; index < workspaces.size(); index++) {
            final int sequence = index + 1;
            final ExecutionWorkspace workspace = workspaces.get(index);
            final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> client.execute(
                    new CodexTurnRequest("Prompt " + sequence, "Instructions.", "model-a", null,
                            this.schemaUnchecked(), workspace)
            ));
            if (index == 0) {
                this.initialize(process);
            }
            final JsonNode threadStart = this.readRequest(process);
            assertThat(threadStart.path("params").path("cwd").asText()).isEqualTo(workspace.cwd().toString());
            assertThat(threadStart.path("params").path("runtimeWorkspaceRoots"))
                    .isEqualTo(this.objectMapper.valueToTree(
                            workspace.workspaceRoots().stream().map(Path::toString).toList()));
            this.replyThread(process, threadStart, "thread-" + sequence);
            final JsonNode turnStart = this.readRequest(process);
            this.replyTurn(process, turnStart, "turn-" + sequence);
            this.complete(process, "thread-" + sequence, "turn-" + sequence,
                    "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");
            assertThat(result.get(1, TimeUnit.SECONDS)).contains("OK");
        }

        assertThat(starter.starts()).isEqualTo(1);
        client.close();
    }

    private TurnHarness startedTurn() throws Exception {
        return this.startedTurn("medium");
    }

    private TurnHarness startedTurn(final String effortId) throws Exception {
        return this.startedTurn(effortId, this.properties());
    }

    private TurnHarness startedTurn(final String effortId, final CodexAppServerProperties properties) throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), properties);
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Analyze auth.", "Instructions.", "model-a", effortId, this.schemaUnchecked(), this.workspace())
        ));
        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        this.replyThread(process, threadStart, "thread-1");
        final JsonNode turnStart = this.readRequest(process);
        this.replyTurn(process, turnStart, "turn-1");
        this.awaitActiveTurn(client);
        return new TurnHarness(client, process, turnStart, result);
    }

    private ExecutionWorkspace workspace() {
        final Path path = Path.of(System.getProperty("java.io.tmpdir"), "forge-agent-codex-runtime");
        try {
            Files.createDirectories(path);
        } catch (final java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
        return this.workspace(path);
    }

    private ExecutionWorkspace workspace(final Path cwd) {
        return new ExecutionWorkspace(cwd, List.of(cwd));
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
        process.writeStdout("{\"id\":\"" + initialize.path("id").asText() + "\",\"result\":{\"userAgent\":\"codex/0.147.0\"}}");
        final JsonNode initialized = this.readRequest(process);
        assertThat(initialized.path("method").asText()).isEqualTo("initialized");
    }

    private void complete(final FakeCodexProcess process, final String threadId, final String turnId, final String output) {
        this.agentMessage(process, threadId, turnId, "final_answer", output);
        this.turnCompleted(process, threadId, turnId, "completed");
    }

    private void agentMessage(final FakeCodexProcess process,
                              final String threadId,
                              final String turnId,
                              final String phase,
                              final String output) {
        final String escaped = output.replace("\\", "\\\\").replace("\"", "\\\"");
        final String phaseJson = phase == null ? "null" : "\"" + phase + "\"";
        process.writeStdout(this.itemCompletedNotification(threadId, turnId, "agentMessage", "\"phase\":" + phaseJson + ",\"text\":\"" + escaped + "\""));
    }

    private void turnCompleted(final FakeCodexProcess process, final String threadId, final String turnId, final String status) {
        process.writeStdout(this.turnCompletedNotification(threadId, turnId, status));
    }

    private void turnCompleted(final FakeCodexProcess process,
                               final String threadId,
                               final String turnId,
                               final String status,
                               final String errorMessage) {
        process.writeStdout(this.turnCompletedNotification(threadId, turnId, status, errorMessage));
    }

    private void assertInterrupt(final FakeCodexProcess process, final String threadId, final String turnId) throws Exception {
        final JsonNode interrupt = this.readRequest(process);
        this.assertInterrupt(interrupt, threadId, turnId);
        process.writeStdout("{\"id\":\"" + interrupt.path("id").asText() + "\",\"result\":{}}");
    }

    private void assertInterrupt(final JsonNode interrupt, final String threadId, final String turnId) {
        assertThat(interrupt.path("method").asText()).isEqualTo("turn/interrupt");
        assertThat(interrupt.path("params").path("threadId").asText()).isEqualTo(threadId);
        assertThat(interrupt.path("params").path("turnId").asText()).isEqualTo(turnId);
    }

    private void replyThread(final FakeCodexProcess process, final JsonNode request) {
        final String model = request.path("params").path("model").asText();
        final String threadId = "model-b".equals(model) ? "thread-b" : "thread-c";
        this.replyThread(process, request, threadId);
    }

    private void replyThread(final FakeCodexProcess process, final JsonNode request, final String threadId) {
        process.writeStdout(this.threadStartResponse(request.path("id").asText(), threadId));
    }

    private void replyTurn(final FakeCodexProcess process, final JsonNode request) {
        final String threadId = request.path("params").path("threadId").asText();
        final String turnId = "thread-b".equals(threadId) ? "turn-b" : "turn-c";
        this.replyTurn(process, request, turnId);
    }

    private void replyTurn(final FakeCodexProcess process, final JsonNode request, final String turnId) {
        process.writeStdout(this.turnStartResponse(request.path("id").asText(), turnId));
    }

    private String threadStartResponse(final String requestId, final String threadId) {
        return this.compactJson("""
                {
                  "id": "%s",
                  "result": {
                    "thread": {
                      "id": "%s",
                      "extra": null,
                      "forkedFromId": null,
                      "parentThreadId": null,
                      "preview": "",
                      "ephemeral": true,
                      "createdAt": 1,
                      "updatedAt": 1,
                      "status": "idle",
                      "cwd": "/tmp/forge-agent-codex-runtime",
                      "cliVersion": "0.147.0",
                      "source": "appServer",
                      "turns": []
                    },
                    "model": "gpt-5.6-luna",
                    "modelProvider": "openai",
                    "cwd": "/tmp/forge-agent-codex-runtime",
                    "runtimeWorkspaceRoots": [],
                    "instructionSources": [],
                    "approvalPolicy": "never",
                    "sandbox": {},
                    "reasoningEffort": null
                  }
                }
                """.formatted(requestId, threadId));
    }

    private String turnStartResponse(final String requestId, final String turnId) {
        return this.compactJson("""
                {
                  "id": "%s",
                  "result": {
                    "turn": {
                      "id": "%s",
                      "items": [],
                      "itemsView": "full",
                      "status": "inProgress",
                      "error": null,
                      "startedAt": 1,
                      "completedAt": null,
                      "durationMs": null
                    }
                  }
                }
                """.formatted(requestId, turnId));
    }

    private String itemCompletedNotification(final String threadId,
                                             final String turnId,
                                             final String itemType,
                                             final String itemFields) {
        return "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"" + threadId + "\",\"turnId\":\"" + turnId
                + "\",\"completedAtMs\":1,\"item\":{\"type\":\"" + itemType + "\"," + itemFields + "}}}";
    }

    private String itemStartedNotification(final String threadId, final String turnId, final String itemType) {
        return "{\"method\":\"item/started\",\"params\":{\"threadId\":\"" + threadId + "\",\"turnId\":\"" + turnId
                + "\",\"item\":{\"type\":\"" + itemType + "\"}}}";
    }

    private String turnCompletedNotification(final String threadId, final String turnId, final String status) {
        return this.turnCompletedNotification(threadId, turnId, status, null);
    }

    private String turnCompletedNotification(final String threadId, final String turnId, final String status, final String errorMessage) {
        final String errorJson = errorMessage == null
                ? ""
                : ",\"error\":{\"message\":\"" + errorMessage.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"" + threadId
                + "\",\"turn\":{\"id\":\"" + turnId + "\",\"status\":\"" + status + "\"" + errorJson + "}}}";
    }

    private String compactJson(final String json) {
        try {
            return this.objectMapper.writeValueAsString(this.objectMapper.readTree(json));
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
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

    private boolean approvalResponse(final JsonNode response) {
        return "decline".equals(response.path("result").path("decision").asText());
    }

    private static void assertExecutionFailure(final CompletableFuture<String> result) {
        assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .satisfies(throwable -> assertThat(throwable.getCause()).isInstanceOf(RuntimeException.class));
    }

    private static void assertExecutionFailure(final CompletableFuture<String> result, final String message) {
        assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .satisfies(throwable -> assertThat(throwable.getCause())
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining(message));
    }

    private record TurnHarness(CodexAppServerClient client, FakeCodexProcess process, JsonNode turnStart, CompletableFuture<String> result) {
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
