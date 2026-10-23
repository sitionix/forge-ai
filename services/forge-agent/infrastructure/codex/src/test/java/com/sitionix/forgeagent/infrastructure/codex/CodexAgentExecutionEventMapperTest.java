package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventType;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodexAgentExecutionEventMapperTest {
    @Test void nativeV2ChildActivityUsesActualChildIdentityAndKind() throws Exception {
        var event=mapper.map("item/completed",json.readTree("""
          {"threadId":"root","turnId":"turn","item":{"type":"subAgentActivity","id":"spawn-v2","kind":"started","agentThreadId":"child-v2","agentPath":"/root/private-child-name","model":"child-model"}}
          """),OBSERVED_AT).orElseThrow();
        assertThat(event.type()).isEqualTo(AgentExecutionEventType.TOOL_CALL);assertThat(event.phase()).isEqualTo("subagent");assertThat(event.status()).isEqualTo(AgentExecutionEventStatus.STARTED);
        assertThat(event.payload()).contains("child-v2","child-model").doesNotContain("/root/private-child-name");
    }
    @Test void nativeChildActivityRetainsRealIdentityWithoutLeakingPrompt() throws Exception {
        var event=mapper.map("item/completed",json.readTree("""
          {"threadId":"root","turnId":"turn","item":{"id":"spawn","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","senderThreadId":"root","receiverThreadIds":["child"],"prompt":"private-instructions-canary","agentsStates":{"child":{"status":"completed","message":"private-child-result"}}}}
          """),OBSERVED_AT).orElseThrow();
        assertThat(event.type()).isEqualTo(AgentExecutionEventType.TOOL_CALL);assertThat(event.phase()).isEqualTo("subagent");
        assertThat(event.payload()).contains("root","child","spawnAgent").doesNotContain("private-instructions-canary","private-child-result");
    }
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-07T09:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final CodexAgentExecutionEventMapper mapper = new CodexAgentExecutionEventMapper(this.json);

    @Test
    void reviewedCredentialFormsAreSafeAcrossProviderTerminalAndApprovedToolEvents() throws Exception {
        mapper.allowMcpTools(Map.of("forge_fixture", Set.of("search")));
        String message = "Rejected body: \"{\\\"token\\\":\\\"escaped-canary\\\"}\"; "
                + "headers={Authorization=[Custom custom-canary], requestId=turn-1}; "
                + "https://host/path?api%5Fkey=encoded-canary&safe=1";
        var params = json.createObjectNode().put("threadId", "t").put("turnId", "u").put("willRetry", false);
        params.putObject("error").put("codexErrorInfo", "badRequest").put("message", message);
        var tracker = new CodexTurnStateTracker();
        var state = tracker.register("t");
        tracker.bindTurnId(state, "u");
        tracker.handleNotification("error", params);
        var failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CompletionException.class,
                () -> state.result().join());
        var terminal = com.sitionix.forgeagent.domain.model.NodeRunFailure.from((RuntimeException) failure.getCause(), "fallback");
        var toolParams = json.createObjectNode().put("threadId", "t").put("turnId", "u");
        var tool = toolParams.putObject("item").put("id", "m1").put("type", "mcpToolCall")
                .put("server", "forge_fixture").put("tool", "search").put("status", "failed");
        tool.putObject("error").put("message", message);
        tool.putObject("arguments").put("query", "argument-canary");
        tool.putObject("result").put("text", "result-canary");
        var providerEvent = mapper.map("error", params, OBSERVED_AT).orElseThrow();
        var toolEvent = mapper.map("item/completed", toolParams, OBSERVED_AT).orElseThrow();
        for (String safe : new String[]{providerEvent.payload(), terminal.message(), toolEvent.payload()}) {
            assertThat(safe).doesNotContain("escaped-canary", "custom-canary", "encoded-canary", "argument-canary", "result-canary")
                    .contains("Rejected body", "requestId=turn-1", "https://host/path", "safe=1");
        }
        assertThat(terminal.code()).isEqualTo("badRequest");
        assertThat(payload(providerEvent).path("willRetry").asBoolean()).isFalse();
        assertThat(toolEvent.providerEventKey()).isEqualTo("item:m1:completed");
    }

    @Test
    void diagnosticCodesSurviveWhileTerminalCodesStayWithinPersistenceWidth() throws Exception {
        for (int length : new int[]{120, 121, 128}) {
            String upstreamCode = "x".repeat(length);
            var error = json.createObjectNode().put("code", upstreamCode).put("message", "Request rejected");
            var failure = CodexTransportException.providerFailure(error, false);
            var terminal = com.sitionix.forgeagent.domain.model.NodeRunFailure.from(failure, "fallback");
            assertThat(terminal.code()).isEqualTo(length <= 120 ? upstreamCode : "AGENT_EXECUTOR_FAILED");
            var params = json.createObjectNode().put("threadId", "t").put("turnId", "u");
            params.set("error", error);
            assertThat(payload(mapper.map("error", params, OBSERVED_AT).orElseThrow()).path("upstreamCode").asText())
                    .isEqualTo(upstreamCode);
            var failedTurn = payload(mapper.turnFailed("u", failure, OBSERVED_AT));
            assertThat(failedTurn.path("code").asText()).isEqualTo(terminal.code());
            assertThat(failedTurn.path("upstreamCode").asText()).isEqualTo(upstreamCode);
        }
    }

    @Test
    void mcpEnabledProviderFailurePreservesReasonCodeCorrelationAndRetryability() throws Exception {
        this.mapper.allowMcpTools(Map.of("forge_allowed", Set.of("search")));
        final var event = map("error", """
                {"threadId":"thread-1","turnId":"turn-1","willRetry":false,"error":{
                 "message":"Request rejected: invalid model. Authorization: Bearer fixture-secret",
                 "codexErrorInfo":"badRequest","additionalDetails":"private payload"}}
                """);
        final var payload = payload(event);
        assertThat(event.status()).isEqualTo(AgentExecutionEventStatus.FAILED);
        assertThat(payload.path("message").asText()).contains("invalid model", "[REDACTED]");
        assertThat(payload.path("providerCode").asText()).isEqualTo("BAD_REQUEST");
        assertThat(payload.path("upstreamCode").asText()).isEqualTo("badRequest");
        assertThat(payload.path("origin").asText()).isEqualTo("PROVIDER");
        assertThat(payload.path("stage").asText()).isEqualTo("TURN_EXECUTION");
        assertThat(payload.path("providerThreadId").asText()).isEqualTo("thread-1");
        assertThat(payload.path("providerTurnId").asText()).isEqualTo("turn-1");
        assertThat(payload.path("willRetry").isBoolean()).isTrue();
        assertThat(payload.path("willRetry").asBoolean()).isFalse();
        assertThat(payload.toString()).doesNotContain("fixture-secret", "private payload", "additionalDetails");
    }

    @Test
    void authenticationAndUnknownProviderCodesRemainTruthfulWithOrWithoutMcp() throws Exception {
        for (boolean mcp : new boolean[]{false, true}) {
            var mapper = new CodexAgentExecutionEventMapper(json);
            if (mcp) mapper.allowMcpTools(Map.of());
            var auth = payload(mapper.map("error", json.readTree("""
                    {"threadId":"t","turnId":"u","willRetry":true,"error":{
                     "message":"Provider authentication rejected; sign in again.",
                     "codexErrorInfo":{"unauthorized":{"httpStatusCode":401}}}}
                    """), OBSERVED_AT).orElseThrow());
            assertThat(auth.path("providerCode").asText()).isEqualTo("UNAUTHORIZED");
            assertThat(auth.path("upstreamCode").asText()).isEqualTo("unauthorized");
            assertThat(auth.path("message").asText()).contains("sign in again");
            assertThat(auth.path("willRetry").asBoolean()).isTrue();
            var unknown = payload(mapper.map("error", json.readTree("""
                    {"threadId":"t","turnId":"u","error":{"codexErrorInfo":"futureProviderCode"}}
                    """), OBSERVED_AT).orElseThrow());
            assertThat(unknown.path("providerCode").asText()).isEqualTo("UNKNOWN");
            assertThat(unknown.path("upstreamCode").asText()).isEqualTo("futureProviderCode");
            assertThat(unknown.path("message").asText()).isEqualTo("Provider execution failed; no reason supplied.");
            assertThat(unknown.has("willRetry")).isFalse();
            assertThat(unknown.toString()).doesNotContain("MCP execution failed");
        }
    }

    @Test
    void approvedMcpToolFailureRetainsOnlySafeErrorFields() throws Exception {
        mapper.allowMcpTools(Map.of("forge_allowed", Set.of("search")));
        var event = map("item/completed", """
                {"threadId":"t","turnId":"u","item":{"id":"m1","type":"mcpToolCall",
                 "server":"forge_allowed","tool":"search","status":"failed",
                 "error":{"code":-32001,"message":"Tool unavailable; api_key=tool-secret",
                          "data":{"input":"private tool input"}},
                 "arguments":{"query":"private argument"},"result":{"text":"private result"}}}
                """);
        var payload = payload(event);
        assertThat(event.providerEventKey()).isEqualTo("item:m1:completed");
        assertThat(event.status()).isEqualTo(AgentExecutionEventStatus.FAILED);
        assertThat(payload.path("message").asText()).contains("Tool unavailable", "[REDACTED]");
        assertThat(payload.path("upstreamCode").asText()).isEqualTo("-32001");
        assertThat(payload.path("origin").asText()).isEqualTo("MCP");
        assertThat(payload.path("stage").asText()).isEqualTo("TOOL_EXECUTION");
        assertThat(payload.toString()).doesNotContain("tool-secret", "private tool input", "private argument", "private result");
    }

    @Test
    void diagnosticsRedactJsonSecretsBasicAuthAndUrlCredentials() throws Exception {
        var params = json.createObjectNode().put("threadId", "t").put("turnId", "u");
        params.putObject("error").put("message", "Rejected: {\"refresh_token\":\"json-secret\"} "
                + "Authorization: Basic dXNlcjpwYXNz https://user:pass@host/path?api_key=query-secret "
                + "password='two word secret' client_secret: colon-secret");
        var event = mapper.map("error", params, OBSERVED_AT).orElseThrow();
        assertThat(event.payload()).contains("Rejected", "[REDACTED]").doesNotContain(
                "json-secret", "dXNlcjpwYXNz", "user:pass", "query-secret", "two word secret", "colon-secret");
    }

    @Test
    void mapsPlanAndVisibleReasoningSummaryWithoutReasoningContent() throws Exception {
        final AgentExecutionEventCandidate plan = map("turn/plan/updated", """
                {"threadId":"thread-1","turnId":"turn-1","explanation":"Next steps","plan":[
                  {"step":"Inspect","status":"completed"},{"step":"Patch","status":"inProgress"}]}
                """);
        assertThat(plan.type()).isEqualTo(AgentExecutionEventType.PLAN);
        assertThat(this.payload(plan)).isEqualTo(this.json.readTree("""
                {"explanation":"Next steps","steps":[{"step":"Inspect","status":"completed"},{"step":"Patch","status":"inProgress"}]}
                """));

        final AgentExecutionEventCandidate reasoning = map("item/completed", """
                {"threadId":"thread-1","turnId":"turn-1","item":{"id":"reason-1","type":"reasoning",
                 "summary":["Checked invariants","Selected transaction boundary"],"content":["private trace"]}}
                """);
        assertThat(reasoning.type()).isEqualTo(AgentExecutionEventType.REASONING_SUMMARY);
        assertThat(reasoning.providerEventKey()).isEqualTo("item:reason-1:completed");
        assertThat(this.payload(reasoning).toString()).doesNotContain("private trace", "content");
        assertThat(this.payload(reasoning).path("summary")).containsExactly(
                this.json.getNodeFactory().textNode("Checked invariants"),
                this.json.getNodeFactory().textNode("Selected transaction boundary"));
    }

    @Test
    void mapsCommandLifecycleAndRetainsIntermediateFailure() throws Exception {
        final AgentExecutionEventCandidate started = map("item/started", """
                {"threadId":"thread-1","turnId":"turn-1","item":{"id":"cmd-1","type":"commandExecution",
                 "command":"mvn test","cwd":"/workspace"}}
                """);
        assertThat(started.type()).isEqualTo(AgentExecutionEventType.COMMAND);
        assertThat(started.status()).isEqualTo(AgentExecutionEventStatus.STARTED);
        assertThat(started.providerEventKey()).isEqualTo("item:cmd-1:started");

        final AgentExecutionEventCandidate failed = map("item/completed", """
                {"threadId":"thread-1","turnId":"turn-1","item":{"id":"cmd-1","type":"commandExecution",
                 "command":"mvn test","cwd":"/workspace","status":"failed","exitCode":1,
                 "aggregatedOutput":"tests failed","durationMs":42}}
                """);
        assertThat(failed.type()).isEqualTo(AgentExecutionEventType.COMMAND);
        assertThat(failed.status()).isEqualTo(AgentExecutionEventStatus.FAILED);
        assertThat(this.payload(failed).path("exitCode").asInt()).isOne();
        assertThat(this.payload(failed).path("output").asText()).isEqualTo("tests failed");

        final AgentExecutionEventCandidate succeeded = map("item/completed", """
                {"threadId":"thread-1","turnId":"turn-1","item":{"id":"cmd-2","type":"commandExecution",
                 "command":"mvn test","status":"completed","exitCode":0}}
                """);
        assertThat(succeeded.status()).isEqualTo(AgentExecutionEventStatus.SUCCEEDED);
    }

    @Test
    void mapsFileToolMessageWarningErrorUsageAndCompaction() throws Exception {
        final AgentExecutionEventCandidate fileChange = map("item/completed", """
                {"threadId":"t","turnId":"u","item":{"id":"f1","type":"fileChange","status":"completed",
                 "changes":[{"path":"src/App.java","kind":"update"}],
                 "files":[{"path":"src/App.java","content":"raw patch must not be stored"}]}}
                """);
        assertThat(fileChange.type()).isEqualTo(AgentExecutionEventType.FILE_CHANGE);
        assertThat(this.payload(fileChange).toString()).contains("src/App.java").doesNotContain("raw patch", "content");

        this.mapper.allowMcpTools(Map.of("drive", Set.of("search")));
        final AgentExecutionEventCandidate tool = map("item/completed", """
                {"threadId":"t","turnId":"u","item":{"id":"m1","type":"mcpToolCall","server":"drive",
                 "tool":"search","operation":"files.search","status":"completed",
                 "arguments":{"password":"do-not-store","task":"private input"},
                 "result":{"access_token":"secret","summary":"found"}}}
                """);
        assertThat(tool.type()).isEqualTo(AgentExecutionEventType.TOOL_CALL);
        assertThat(this.payload(tool).path("toolKind").asText()).isEqualTo("MCP");
        assertThat(this.payload(tool).path("tool").asText()).isEqualTo("search");
        assertThat(this.payload(tool).path("server").asText()).isEqualTo("drive");
        assertThat(this.payload(tool).path("responseSummary").asText()).isEqualTo("completed");
        assertThat(this.payload(tool).toString()).doesNotContain("files.search", "found",
                "do-not-store", "private input", "arguments", "requestSummary", "\"secret\"");

        final AgentExecutionEventCandidate message = map("item/completed", """
                {"threadId":"t","turnId":"u","item":{"id":"a1","type":"agentMessage","phase":"final_answer","text":"done"}}
                """);
        assertThat(message.type()).isEqualTo(AgentExecutionEventType.AGENT_MESSAGE);
        assertThat(message.phase()).isEqualTo("FINAL");

        assertThat(map("warning", "{\"threadId\":\"t\",\"turnId\":\"u\",\"message\":\"careful\"}").type())
                .isEqualTo(AgentExecutionEventType.WARNING);
        assertThat(map("error", "{\"threadId\":\"t\",\"turnId\":\"u\",\"error\":{\"message\":\"boom\"}}").type())
                .isEqualTo(AgentExecutionEventType.ERROR);

        final JsonNode usage = this.payload(map("thread/tokenUsage/updated", """
                {"threadId":"t","turnId":"u","tokenUsage":{"total":{"inputTokens":12,"cachedInputTokens":3,
                 "outputTokens":7,"reasoningOutputTokens":2},"last":{"inputTokens":4},"modelContextWindow":200000}}
                """));
        assertThat(usage.path("total").path("input").asLong()).isEqualTo(12);
        assertThat(usage.path("total").path("cached").asLong()).isEqualTo(3);
        assertThat(usage.path("modelContextWindow").asLong()).isEqualTo(200000);

        assertThat(map("thread/compacted", "{\"threadId\":\"t\",\"turnId\":\"u\"}").type())
                .isEqualTo(AgentExecutionEventType.CONTEXT_COMPACTION);
        assertThat(map("item/completed", """
                {"threadId":"t","turnId":"u","item":{"id":"c1","type":"contextCompaction","status":"completed"}}
                """).type()).isEqualTo(AgentExecutionEventType.CONTEXT_COMPACTION);
    }

    @Test
    void mcpActivityIgnoresRawResultFailureAndUnknownTools() throws Exception {
        this.mapper.allowMcpTools(Map.of("forge_allowed", Set.of("search")));
        var event = map("item/completed", """
                {"threadId":"t","turnId":"u","item":{"id":"m1","type":"mcpToolCall",
                 "server":"forge_allowed","tool":"search","status":"synthetic-canary",
                 "result":{"text":"synthetic-canary"},"error":{"message":"synthetic-canary"}}}
                """);
        assertThat(event.payload()).doesNotContain("synthetic-canary");
        assertThat(optional("item/completed", """
                {"threadId":"t","turnId":"u","item":{"id":"m2","type":"mcpToolCall",
                 "server":"unapproved","tool":"search","result":"synthetic-canary"}}
                """)).isEmpty();
        assertThat(this.mapper.turnFailed("turn-1", "Invalid model TOKEN=synthetic-canary", OBSERVED_AT).payload())
                .contains("Invalid model", "[REDACTED]")
                .doesNotContain("synthetic-canary");
        final JsonNode providerError = this.payload(map("error", """
                {"threadId":"t","turnId":"u","error":{"message":"Invalid model TOKEN=synthetic-canary",
                 "codexErrorInfo":"badRequest"}}
                """));
        assertThat(providerError.path("message").asText())
                .isEqualTo("Invalid model TOKEN=[REDACTED]");
        assertThat(providerError.path("providerCode").asText()).isEqualTo("BAD_REQUEST");
        assertThat(providerError.toString()).doesNotContain("synthetic-canary");

        final AgentExecutionEventCandidate retrying = map("error", """
                {"threadId":"t","turnId":"u","willRetry":true,"error":{"message":"Overloaded TOKEN=synthetic-canary",
                 "codexErrorInfo":"serverOverloaded"}}
                """);
        assertThat(retrying.status()).isNull();
        final JsonNode retryingPayload = this.payload(retrying);
        assertThat(retryingPayload.path("providerCode").asText()).isEqualTo("SERVER_OVERLOADED");
        assertThat(retryingPayload.path("willRetry").asBoolean()).isTrue();
        assertThat(retryingPayload.path("message").asText()).isEqualTo("Overloaded TOKEN=[REDACTED]");
        assertThat(retryingPayload.toString()).doesNotContain("synthetic-canary");
    }

    @Test
    void ignoresHighFrequencyDeltasAndNonFinalMessages() throws Exception {
        assertThat(optional("item/agentMessage/delta", "{\"threadId\":\"t\",\"turnId\":\"u\",\"delta\":\"x\"}"))
                .isEmpty();
        assertThat(optional("item/reasoning/summaryTextDelta", "{\"threadId\":\"t\",\"turnId\":\"u\",\"delta\":\"x\"}"))
                .isEmpty();
        assertThat(optional("item/commandExecution/outputDelta", "{\"threadId\":\"t\",\"turnId\":\"u\",\"delta\":\"x\"}"))
                .isEmpty();
        assertThat(optional("item/completed", """
                {"threadId":"t","turnId":"u","item":{"id":"a1","type":"agentMessage","phase":"commentary","text":"working"}}
                """)).isEmpty();
    }

    @Test
    void truncatesLargeTextWithExplicitMetadataAndRedactsSecretAssignments() throws Exception {
        final String output = "TOKEN=very-secret-value\n" + "x".repeat(70_000);
        final JsonNode params = this.json.createObjectNode()
                .put("threadId", "t").put("turnId", "u")
                .set("item", this.json.createObjectNode().put("id", "cmd").put("type", "commandExecution")
                        .put("command", "API_KEY=top-secret run-tests").put("status", "completed")
                        .put("exitCode", 0).put("aggregatedOutput", output));

        final AgentExecutionEventCandidate event = this.mapper.map("item/completed", params, OBSERVED_AT).orElseThrow();
        final JsonNode payload = this.payload(event);

        assertThat(payload.path("command").asText()).doesNotContain("top-secret");
        assertThat(payload.path("output").asText()).doesNotContain("very-secret-value");
        assertThat(payload.path("truncated").asBoolean()).isTrue();
        assertThat(payload.path("originalBytes").asLong()).isGreaterThan(payload.path("storedBytes").asLong());
    }

    @Test
    void largeMcpResultIsNeverStoredInActivity() throws Exception {
        this.mapper.allowMcpTools(Map.of("forge_allowed", Set.of("search")));
        final JsonNode params = this.json.createObjectNode()
                .put("threadId", "t").put("turnId", "u")
                .set("item", this.json.createObjectNode().put("id", "tool").put("type", "mcpToolCall")
                        .put("server", "forge_allowed").put("tool", "search").put("status", "completed")
                        .set("result", this.json.createObjectNode().put("summary", "x".repeat(140_000))));

        final JsonNode payload = this.payload(this.mapper.map("item/completed", params, OBSERVED_AT).orElseThrow());

        assertThat(payload.path("responseSummary").asText()).isEqualTo("completed");
        assertThat(payload.toString()).doesNotContain("xxx");
    }

    private AgentExecutionEventCandidate map(final String method, final String params) throws Exception {
        return optional(method, params).orElseThrow();
    }

    private Optional<AgentExecutionEventCandidate> optional(final String method, final String params) throws Exception {
        return this.mapper.map(method, this.json.readTree(params), OBSERVED_AT);
    }

    private JsonNode payload(final AgentExecutionEventCandidate event) throws Exception {
        return this.json.readTree(event.payload());
    }
}
