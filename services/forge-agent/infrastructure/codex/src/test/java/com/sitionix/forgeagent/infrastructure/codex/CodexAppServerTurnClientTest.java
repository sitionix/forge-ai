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
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CodexAppServerTurnClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void durableExecutionPersistsThreadBeforeTurnStartAndTurnBeforeNotifications() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final List<String> ordering = new ArrayList<>();
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> client.executeDurable(
                new CodexTurnRequest("Analyze auth.", "Instructions.", "model-a", null, this.schemaUnchecked(), this.workspace()),
                null, new CodexExecutionIdentityCallbacks() {
                    public void conversationStarted(String id, String version) { ordering.add("thread:" + id); }
                    public void turnStarted(String id) { ordering.add("turn:" + id); }
                }));
        this.initialize(process);
        final JsonNode threadStart=this.readRequest(process);
        assertThat(threadStart.path("params").path("ephemeral").asBoolean()).isFalse();
        this.replyThread(process,threadStart,"thread-durable");
        final JsonNode turnStart=this.readRequest(process);
        assertThat(ordering).containsExactly("thread:thread-durable");
        this.replyTurn(process,turnStart,"turn-1");
        this.complete(process,"thread-durable","turn-1","{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");
        assertThat(result.get(1,TimeUnit.SECONDS)).contains("OK");
        assertThat(ordering).containsExactly("thread:thread-durable","turn:turn-1");
        client.close();
    }

    @Test
    void durableResumeNeverStartsReplacementThread() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerClient client = this.client(new FakeStarter(process), this.properties());
        final CompletableFuture<String> result=CompletableFuture.supplyAsync(() -> client.executeDurable(
                new CodexTurnRequest("Continue.","Instructions.","model-a",null,this.schemaUnchecked(),this.workspace()),
                "thread-existing", new CodexExecutionIdentityCallbacks() {
                    public void conversationStarted(String id,String version) { throw new AssertionError(); }
                    public void turnStarted(String id) { }
                }));
        this.initialize(process);
        final JsonNode resume=this.readRequest(process);
        assertThat(resume.path("method").asText()).isEqualTo("thread/resume");
        assertThat(resume.path("params").path("excludeTurns").asBoolean()).isTrue();
        process.writeStdout("{\"id\":\""+resume.path("id").asText()+"\",\"error\":{\"code\":-32600,\"message\":\"missing\"}}");
        assertThatThrownBy(() -> result.get(1,TimeUnit.SECONDS)).hasCauseInstanceOf(CodexTransportException.class);
        assertThat(process.pendingClientRequestBytes()).isZero();
        client.close();
    }

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
                          "web_search": "disabled",
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
        this.complete(process, "thread-1", "turn-1", "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");

        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");
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
    void requestExecutionWorkspaceControlsThreadCwdRegardlessOfAppServerProperties() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerProperties properties = this.properties();
        final Path configuredCwd = Files.createTempDirectory("forge-agent-codex-process-cwd");
        final Path requestCwd = Files.createTempDirectory("forge-agent-codex-request-cwd");
        properties.setRuntimeCwd(configuredCwd.toString());
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerClient client = this.client(starter, properties);
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Analyze auth.", "Instructions.", "gpt-5.6-luna", null,
                        this.schemaUnchecked(), this.workspace(requestCwd))
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        assertThat(threadStart.path("params").path("cwd").asText())
                .isEqualTo(requestCwd.toAbsolutePath().normalize().toString())
                .isNotEqualTo(configuredCwd.toAbsolutePath().normalize().toString());
        assertThat(starter.workingDirectories()).containsExactly(requestCwd);
        this.replyThread(process, threadStart, "thread-1");
        final JsonNode turnStart = this.readRequest(process);
        this.replyTurn(process, turnStart, "turn-1");
        this.complete(process, "thread-1", "turn-1", "{\"summary\":\"OK\",\"riskLevel\":\"LOW\"}");

        assertThat(result.get(1, TimeUnit.SECONDS)).contains("OK");
        client.close();
    }

    @Test
    void requestWorkspaceRootsControlThreadRuntimeWorkspaceRoots() throws Exception {
        final FakeCodexProcess process = new FakeCodexProcess(false, true);
        final CodexAppServerProperties properties = this.properties();
        final Path configuredCwd = Files.createTempDirectory("forge-agent-codex-process-cwd");
        final Path requestCwd = Files.createTempDirectory("forge-agent-codex-project-cwd");
        final Path repositoryA = Files.createDirectories(requestCwd.resolve("backend"));
        final Path repositoryB = Files.createDirectories(requestCwd.resolve("frontend"));
        properties.setRuntimeCwd(configuredCwd.toString());
        final FakeStarter starter = new FakeStarter(process);
        final CodexAppServerClient client = this.client(starter, properties);
        final ExecutionWorkspace requestWorkspace = new ExecutionWorkspace(
                requestCwd,
                List.of(repositoryA, repositoryB)
        );
        final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Create a file.", "Instructions.", "gpt-5.6-spark", null,
                        this.schemaUnchecked(), requestWorkspace)
        ));

        this.initialize(process);
        final JsonNode threadStart = this.readRequest(process);
        assertThat(threadStart.path("params").path("sandbox").asText()).isEqualTo("workspace-write");
        assertThat(threadStart.path("params").path("cwd").asText()).isEqualTo(requestWorkspace.cwd().toString());
        assertThat(starter.workingDirectories()).containsExactly(requestWorkspace.cwd());
        assertThat(threadStart.path("params").path("runtimeWorkspaceRoots"))
                .isEqualTo(this.objectMapper.valueToTree(List.of(repositoryA.toString(), repositoryB.toString())));
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
    void live01532IdleSequenceCompletesWithoutTurnCompleted() throws Exception {
        final TurnHarness harness = this.startedTurn();

        this.agentMessage(harness.process(), "thread-1", "turn-1", "commentary", "{\"summary\":\"Intermediate\",\"riskLevel\":\"HIGH\"}");
        this.agentMessage(harness.process(), "thread-1", "turn-1", "final_answer", "{\"summary\":\"Final\",\"riskLevel\":\"LOW\"}");
        harness.process().writeStdout("{\"method\":\"thread/status/changed\",\"params\":{\"threadId\":\"thread-1\",\"status\":{\"type\":\"idle\"}}}");

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

        assertExecutionFailure(harness.result(), "Unsupported Codex generation item type: mcpToolCall");
        harness.client().close();
    }

    @Test
    void unknownGenerationItemFailsExactTurnAndInterrupts() throws Exception {
        final TurnHarness harness = this.startedTurn();

        harness.process().writeStdout(this.itemStartedNotification("thread-1", "turn-1", "unknownNewItem"));
        this.assertInterrupt(harness.process(), "thread-1", "turn-1");

        assertExecutionFailure(harness.result(), "Unsupported Codex generation item type: unknownNewItem");
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
        client.close();
    }

    @Test
    void concurrentTurnsUseWorkspaceBoundProcessesAndReceiveOnlyTheirOwnOutput() throws Exception {
        final FakeCodexProcess firstProcess = new FakeCodexProcess(false, true);
        final FakeCodexProcess secondProcess = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(firstProcess, secondProcess);
        final CodexAppServerClient client = this.client(starter, this.properties());
        final JsonNode schema = this.schema();
        final Path project = Files.createTempDirectory("forge-agent-concurrent-project");
        final Path repositoryA = Files.createDirectories(project.resolve("repo-A"));
        final Path repositoryB = Files.createDirectories(project.resolve("repo-B"));
        final CompletableFuture<String> b = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt B", "Instructions.", "model-b", null, schema,
                        new ExecutionWorkspace(repositoryA, List.of(repositoryA)))
        ));
        final CompletableFuture<String> c = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt C", "Instructions.", "model-c", null, schema,
                        new ExecutionWorkspace(repositoryB, List.of(repositoryB)))
        ));

        this.initialize(firstProcess);
        this.initialize(secondProcess);
        final JsonNode threadOne = this.readRequest(firstProcess);
        final JsonNode threadTwo = this.readRequest(secondProcess);
        this.assertProcessWorkspace(starter, firstProcess, threadOne, repositoryA, repositoryB);
        this.assertProcessWorkspace(starter, secondProcess, threadTwo, repositoryA, repositoryB);
        this.replyThread(firstProcess, threadOne);
        this.replyThread(secondProcess, threadTwo);
        final JsonNode turnOne = this.readRequest(firstProcess);
        final JsonNode turnTwo = this.readRequest(secondProcess);
        this.replyTurn(firstProcess, turnOne);
        this.replyTurn(secondProcess, turnTwo);
        this.complete(firstProcess, threadOne.path("params").path("model").asText().replace("model-", "thread-"),
                threadOne.path("params").path("model").asText().replace("model-", "turn-"),
                this.outputForModel(threadOne));
        this.complete(secondProcess, threadTwo.path("params").path("model").asText().replace("model-", "thread-"),
                threadTwo.path("params").path("model").asText().replace("model-", "turn-"),
                this.outputForModel(threadTwo));

        assertThat(b.get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"B\",\"riskLevel\":\"HIGH\"}");
        assertThat(c.get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"C\",\"riskLevel\":\"LOW\"}");
        assertThat(starter.starts()).isEqualTo(2);
        client.close();
    }

    @Test
    void concurrentWorkspaceBoundProcessesWithEarlyNotificationsRemainIsolated() throws Exception {
        final FakeCodexProcess firstProcess = new FakeCodexProcess(false, true);
        final FakeCodexProcess secondProcess = new FakeCodexProcess(false, true);
        final FakeStarter starter = new FakeStarter(firstProcess, secondProcess);
        final CodexAppServerClient client = this.client(starter, this.properties());
        final JsonNode schema = this.schema();
        final CompletableFuture<String> b = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt B", "Instructions.", "model-b", null, schema, this.workspace())
        ));
        final CompletableFuture<String> c = CompletableFuture.supplyAsync(() -> client.execute(
                new CodexTurnRequest("Prompt C", "Instructions.", "model-c", null, schema, this.workspace())
        ));

        this.initialize(firstProcess);
        this.initialize(secondProcess);
        final JsonNode threadOne = this.readRequest(firstProcess);
        final JsonNode threadTwo = this.readRequest(secondProcess);
        this.replyThread(firstProcess, threadOne);
        this.replyThread(secondProcess, threadTwo);
        final JsonNode turnOne = this.readRequest(firstProcess);
        final JsonNode turnTwo = this.readRequest(secondProcess);
        this.complete(firstProcess, threadOne.path("params").path("model").asText().replace("model-", "thread-"),
                threadOne.path("params").path("model").asText().replace("model-", "turn-"),
                this.earlyOutputForModel(threadOne));
        this.complete(secondProcess, threadTwo.path("params").path("model").asText().replace("model-", "thread-"),
                threadTwo.path("params").path("model").asText().replace("model-", "turn-"),
                this.earlyOutputForModel(threadTwo));
        this.replyTurn(secondProcess, turnTwo);
        this.replyTurn(firstProcess, turnOne);

        assertThat(b.get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"B early\",\"riskLevel\":\"LOW\"}");
        assertThat(c.get(1, TimeUnit.SECONDS)).isEqualTo("{\"summary\":\"C early\",\"riskLevel\":\"HIGH\"}");
        assertThat(starter.starts()).isEqualTo(2);
        client.close();
    }

    @Test
    void eachExecutionLaunchesProcessBoundToItsImmutableWorkspace() throws Exception {
        final List<FakeCodexProcess> processes = List.of(
                new FakeCodexProcess(false, true),
                new FakeCodexProcess(false, true),
                new FakeCodexProcess(false, true)
        );
        final FakeStarter starter = new FakeStarter(processes.toArray(FakeCodexProcess[]::new));
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
            final FakeCodexProcess process = processes.get(index);
            final CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> client.execute(
                    new CodexTurnRequest("Prompt " + sequence, "Instructions.", "model-a", null,
                            this.schemaUnchecked(), workspace)
            ));
            this.initialize(process);
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

        assertThat(starter.workingDirectories()).containsExactly(repositoryA, repositoryB, project);
        assertThat(starter.starts()).isEqualTo(3);
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

    private String outputForModel(final JsonNode threadStart) {
        return "model-b".equals(threadStart.path("params").path("model").asText())
                ? "{\"summary\":\"B\",\"riskLevel\":\"HIGH\"}"
                : "{\"summary\":\"C\",\"riskLevel\":\"LOW\"}";
    }

    private String earlyOutputForModel(final JsonNode threadStart) {
        return "model-b".equals(threadStart.path("params").path("model").asText())
                ? "{\"summary\":\"B early\",\"riskLevel\":\"LOW\"}"
                : "{\"summary\":\"C early\",\"riskLevel\":\"HIGH\"}";
    }

    private void assertProcessWorkspace(final FakeStarter starter,
                                        final FakeCodexProcess process,
                                        final JsonNode threadStart,
                                        final Path repositoryA,
                                        final Path repositoryB) {
        final Path expected = "model-b".equals(threadStart.path("params").path("model").asText())
                ? repositoryA
                : repositoryB;
        assertThat(starter.workingDirectory(process)).isEqualTo(expected);
        assertThat(threadStart.path("params").path("cwd").asText()).isEqualTo(expected.toString());
        assertThat(threadStart.path("params").path("runtimeWorkspaceRoots"))
                .isEqualTo(this.objectMapper.valueToTree(List.of(expected.toString())));
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
                      "cliVersion": "0.147.0",
                      "source": "appServer",
                      "turns": []
                    },
                    "model": "gpt-5.6-luna",
                    "modelProvider": "openai",
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
        return new CodexAppServerClient(this.objectMapper, starter, properties, new CodexRuntimeWorkspace(properties));
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
        private final List<Path> workingDirectories = new ArrayList<>();
        private final List<Launch> launches = new ArrayList<>();
        private int starts;

        private FakeStarter(final FakeCodexProcess... processes) {
            this.processes = new ArrayDeque<>(List.of(processes));
        }

        @Override
        public synchronized StartedCodexAppServer start(final Path workingDirectory) {
            this.starts++;
            this.workingDirectories.add(workingDirectory);
            final FakeCodexProcess process = this.processes.remove();
            this.launches.add(new Launch(process, workingDirectory));
            return new StartedCodexAppServer(process, List.of("codex", "app-server", "--stdio"), Instant.now());
        }

        private int starts() {
            return this.starts;
        }

        private List<Path> workingDirectories() {
            return List.copyOf(this.workingDirectories);
        }

        private synchronized Path workingDirectory(final FakeCodexProcess process) {
            return this.launches.stream()
                    .filter(launch -> launch.process() == process)
                    .findFirst()
                    .orElseThrow()
                    .workingDirectory();
        }

        private record Launch(FakeCodexProcess process, Path workingDirectory) {
        }
    }
}
