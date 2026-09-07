package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventType;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodexAgentExecutionEventMapperTest {
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-07T09:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final CodexAgentExecutionEventMapper mapper = new CodexAgentExecutionEventMapper(this.json);

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
        assertThat(this.payload(tool).path("operation").asText()).isEqualTo("files.search");
        assertThat(this.payload(tool).toString()).contains("[REDACTED]", "found")
                .doesNotContain("do-not-store", "private input", "arguments", "requestSummary", "\"secret\"");

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
    void boundsLargeStructuredPayloadWithExplicitMetadata() throws Exception {
        final JsonNode params = this.json.createObjectNode()
                .put("threadId", "t").put("turnId", "u")
                .set("item", this.json.createObjectNode().put("id", "tool").put("type", "mcpToolCall")
                        .put("tool", "search").put("status", "completed")
                        .set("result", this.json.createObjectNode().put("summary", "x".repeat(140_000))));

        final JsonNode payload = this.payload(this.mapper.map("item/completed", params, OBSERVED_AT).orElseThrow());

        assertThat(payload.path("truncated").asBoolean()).isTrue();
        assertThat(payload.path("originalBytes").asLong()).isGreaterThan(payload.path("storedBytes").asLong());
        assertThat(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(131_072);
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
