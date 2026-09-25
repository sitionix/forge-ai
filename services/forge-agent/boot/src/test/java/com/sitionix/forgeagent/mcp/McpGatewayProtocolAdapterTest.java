package com.sitionix.forgeagent.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.McpGatewayRuntime;
import com.sitionix.forgeagent.infrastructure.local.mcp.gateway.SdkMcpGatewayToolView;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class McpGatewayProtocolAdapterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpGatewayRuntime runtime = mock(McpGatewayRuntime.class);
    private final SdkMcpGatewayToolView views = mock(SdkMcpGatewayToolView.class);
    private final UUID connectionId = UUID.randomUUID();
    private final String token = "synthetic-runtime-token";
    private final McpAllowedTool approval = new McpAllowedTool("read", "sha256:" + "a".repeat(64));
    private final McpRuntimeGrant grant = new McpRuntimeGrant(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "owner", 1L, connectionId, URI.create("https://example.org/mcp"), McpAuthType.NONE,
            "none", Set.of(approval), Instant.now().plusSeconds(60));
    private final McpGatewayProtocolAdapter adapter = new McpGatewayProtocolAdapter(runtime, views, json,
            Duration.ofSeconds(5));

    @Test void sdkHandlerInitializesListsAndCallsOnlyApprovedTool() throws Exception {
        when(views.tools(grant.id())).thenReturn(List.of(McpSchema.Tool.builder().name("read")
                .inputSchema(new JacksonMcpJsonMapper(json), "{\"type\":\"object\"}").build()));
        byte[] init = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}}");
        var capabilities = json.readTree(adapter.process(grant, token, init).body())
                .path("result").path("capabilities");
        assertThat(capabilities.has("tools")).isTrue();
        assertThat(capabilities.has("logging")).isFalse();
        assertThat(capabilities.has("resources")).isFalse();
        assertThat(capabilities.has("prompts")).isFalse();
        var listed = json.readTree(adapter.process(grant, token,
                request("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")).body());
        assertThat(listed.path("result").path("tools")).hasSize(1);
        when(runtime.call(token, connectionId, "read", approval.schemaFingerprint(), "{}"))
                .thenReturn(new McpToolCallResult(false, "[{\"type\":\"text\",\"text\":\"ok\"}]", null));
        var called = json.readTree(adapter.process(grant, token,
                request("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"read\",\"arguments\":{}}}")).body());
        assertThat(called.path("result").path("content").get(0).path("text").asText()).isEqualTo("ok");
        verify(runtime).call(token, connectionId, "read", approval.schemaFingerprint(), "{}");
    }

    @Test void unsafeExceptionAndMalformedBodyNeverReturnRawCanary() {
        when(views.tools(grant.id())).thenReturn(List.of(McpSchema.Tool.builder().name("read")
                .inputSchema(new JacksonMcpJsonMapper(json), "{\"type\":\"object\"}").build()));
        when(runtime.call(anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("synthetic-secret-canary"));
        var failed = adapter.process(grant, token, request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"read\",\"arguments\":{}}}"));
        assertThat(new String(failed.body(), StandardCharsets.UTF_8)).doesNotContain("synthetic-secret-canary");
        var malformed = adapter.process(grant, token, request("{broken-synthetic-secret-canary"));
        assertThat(new String(malformed.body(), StandardCharsets.UTF_8)).doesNotContain("synthetic-secret-canary");
        var unsupported = adapter.process(grant, token,
                request("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"sampling/createMessage\",\"params\":{}}"));
        assertThat(new String(unsupported.body(), StandardCharsets.UTF_8)).contains("MCP method unavailable");
        verify(runtime, times(1)).call(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test void successfulStructuredToolResultIsNotLogged(CapturedOutput output) {
        when(views.tools(grant.id())).thenReturn(List.of(McpSchema.Tool.builder().name("read")
                .inputSchema(new JacksonMcpJsonMapper(json), "{\"type\":\"object\"}").build()));
        when(runtime.call(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new McpToolCallResult(false, "[{\"type\":\"text\",\"text\":\"ok\"}]",
                        "{\"secret\":\"synthetic-secret-canary\"}"));
        var result = adapter.process(grant, token, request("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"read\",\"arguments\":{}}}"));
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).contains("synthetic-secret-canary");
        assertThat(output.getAll()).doesNotContain("synthetic-secret-canary");
    }

    private static byte[] request(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
