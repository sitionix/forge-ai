package com.sitionix.forgeagent.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.mcp.McpGatewayAccessException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.McpGatewayRuntime;
import com.sitionix.forgeagent.infrastructure.local.mcp.gateway.SdkMcpGatewayToolView;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = McpGatewaySdkHttpTest.TestApp.class,
        properties = {"forge.mcp.enabled=true", "forge.mcp.gateway.max-request-bytes=1024"})
class McpGatewaySdkHttpTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class,
            MongoAutoConfiguration.class, MongoDataAutoConfiguration.class})
    @Import(McpGatewayController.class)
    static class TestApp {
        @Bean McpGatewayRuntime runtime() { return mock(McpGatewayRuntime.class); }
        @Bean SdkMcpGatewayToolView views() { return mock(SdkMcpGatewayToolView.class); }
        @Bean McpGatewayProtocolAdapter protocol(McpGatewayRuntime runtime, SdkMcpGatewayToolView views,
                                                 ObjectMapper json) {
            return new McpGatewayProtocolAdapter(runtime, views, json, Duration.ofSeconds(5));
        }
        @Bean FilterRegistrationBean<McpGatewayRuntimeFilter> runtimeFilter(McpGatewayRuntime runtime) {
            var registration = new FilterRegistrationBean<>(new McpGatewayRuntimeFilter(runtime, Set.of("localhost")));
            registration.addUrlPatterns("/*");
            return registration;
        }
    }

    @LocalServerPort int port;
    @Autowired McpGatewayRuntime runtime;
    @Autowired SdkMcpGatewayToolView views;
    @Autowired ObjectMapper json;
    private final UUID connectionId = UUID.randomUUID();
    private final String token = "synthetic-runtime-token";
    private final McpAllowedTool approval = new McpAllowedTool("read", "sha256:" + "a".repeat(64));
    private final McpRuntimeGrant grant = new McpRuntimeGrant(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "owner", 1L, connectionId, URI.create("https://example.org/mcp"), McpAuthType.NONE,
            "none", Set.of(approval), Instant.now().plusSeconds(60));

    @BeforeEach void grant() {
        reset(runtime, views);
        when(runtime.authorize(token, connectionId)).thenReturn(grant);
        when(views.tools(grant.id())).thenReturn(java.util.List.of(McpSchema.Tool.builder().name("read")
                .inputSchema(new JacksonMcpJsonMapper(json), "{\"type\":\"object\"}").build()));
    }

    @Test void standardSdkClientCanInitializeListAndCallOverRealHttp() {
        when(runtime.call(token, connectionId, "read", approval.schemaFingerprint(), "{}"))
                .thenReturn(new McpToolCallResult(false, "[{\"type\":\"text\",\"text\":\"ok\"}]", null));
        var request = HttpRequest.newBuilder().header("Authorization", "Bearer " + token);
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/internal/mcp/connections/" + connectionId)
                .requestBuilder(request).openConnectionOnStartup(false).build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
            assertThat(client.initialize().capabilities().tools()).isNotNull();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactly("read");
            var result = client.callTool(new McpSchema.CallToolRequest("read", Map.of()));
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).hasSize(1);
        }
        verify(runtime).call(token, connectionId, "read", approval.schemaFingerprint(), "{}");
    }

    @Test void wrongBearerCannotInvokeToolOverRealHttp() {
        var request = HttpRequest.newBuilder().header("Authorization", "Bearer wrong");
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/internal/mcp/connections/" + connectionId)
                .requestBuilder(request).openConnectionOnStartup(false).build();
        when(runtime.authorize("wrong", connectionId)).thenThrow(new McpGatewayAccessException());
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(3)).build()) {
            assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);
        }
        verify(runtime, never()).call(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test void equalToolNamesInTwoGrantsRouteToTheirOwnConnection() throws Exception {
        UUID otherConnection = UUID.randomUUID();
        String otherToken = "synthetic-other-runtime-token";
        var otherApproval = new McpAllowedTool("read", "sha256:" + "b".repeat(64));
        var otherGrant = new McpRuntimeGrant(UUID.randomUUID(), grant.installationId(), grant.sessionId(),
                UUID.randomUUID(), grant.nodeRunId(), grant.workflowRunId(), grant.projectId(), "owner", 2L,
                otherConnection, URI.create("https://other.example.org/mcp"), McpAuthType.NONE, "none",
                Set.of(otherApproval), Instant.now().plusSeconds(60));
        when(runtime.authorize(otherToken, otherConnection)).thenReturn(otherGrant);
        when(views.tools(otherGrant.id())).thenReturn(java.util.List.of(McpSchema.Tool.builder().name("read")
                .inputSchema(new JacksonMcpJsonMapper(json), "{\"type\":\"object\"}").build()));
        when(runtime.call(token, connectionId, "read", approval.schemaFingerprint(), "{}"))
                .thenReturn(new McpToolCallResult(false, "[{\"type\":\"text\",\"text\":\"first\"}]", null));
        when(runtime.call(otherToken, otherConnection, "read", otherApproval.schemaFingerprint(), "{}"))
                .thenReturn(new McpToolCallResult(false, "[{\"type\":\"text\",\"text\":\"second\"}]", null));
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"read\",\"arguments\":{}}}";
        String first = post(connectionId, token, payload);
        String second = post(otherConnection, otherToken, payload);
        assertThat(first).contains("first").doesNotContain("second");
        assertThat(second).contains("second").doesNotContain("first");
        verify(runtime).call(token, connectionId, "read", approval.schemaFingerprint(), "{}");
        verify(runtime).call(otherToken, otherConnection, "read", otherApproval.schemaFingerprint(), "{}");
    }

    @Test void guessedToolRevocationAndOversizedBodyMakeZeroAdditionalCalls() throws Exception {
        String guessed = post(connectionId, token,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"write\",\"arguments\":{}}}");
        assertThat(guessed).contains("MCP tool unavailable");
        verify(runtime, never()).call(anyString(), any(), anyString(), anyString(), anyString());

        var oversized = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/internal/mcp/connections/" + connectionId))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(1025))).build();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(client.send(oversized, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        }
        verify(runtime, never()).call(anyString(), any(), anyString(), anyString(), anyString());

        when(runtime.authorize(token, connectionId)).thenThrow(new McpGatewayAccessException());
        var revoked = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/internal/mcp/connections/" + connectionId))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(client.send(revoked, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        verify(runtime, never()).call(anyString(), any(), anyString(), anyString(), anyString());
    }

    private String post(UUID connection, String bearer, String payload) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/internal/mcp/connections/" + connection))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        }
    }
}
