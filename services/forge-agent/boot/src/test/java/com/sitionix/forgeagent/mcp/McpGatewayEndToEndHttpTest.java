package com.sitionix.forgeagent.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.mcp.McpGatewayService;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.infrastructure.local.mcp.gateway.InMemoryMcpRuntimeGrantRepository;
import com.sitionix.forgeagent.infrastructure.local.mcp.gateway.SdkMcpGatewayToolView;
import com.sitionix.forgeagent.infrastructure.local.mcp.protocol.SdkMcpRemoteClient;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        classes = McpGatewayEndToEndHttpTest.TestApp.class,
        properties = "forge.mcp.enabled=true")
class McpGatewayEndToEndHttpTest {
    private static final String SECRET = "synthetic-external-credential";

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class,
            MongoAutoConfiguration.class, MongoDataAutoConfiguration.class})
    @Import(McpGatewayController.class)
    static class TestApp {
        @Bean UpstreamFixture upstream() throws Exception { return new UpstreamFixture(); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean AgentExecutionSessionRepository sessions() { return mock(AgentExecutionSessionRepository.class); }
        @Bean NodeRunRepository nodes() { return mock(NodeRunRepository.class); }
        @Bean WorkflowRunRepository workflows() { return mock(WorkflowRunRepository.class); }
        @Bean ProjectRepository projects() { return mock(ProjectRepository.class); }
        @Bean McpConnectionRepository connections() { return mock(McpConnectionRepository.class); }
        @Bean ForgeInstanceIdentityRepository identity() { return mock(ForgeInstanceIdentityRepository.class); }
        @Bean McpCredentialCipher cipher() { return mock(McpCredentialCipher.class); }
        @Bean McpRuntimeGrantRepository grants(Clock clock) {
            return new InMemoryMcpRuntimeGrantRepository(clock, System::nanoTime, 10);
        }
        @Bean SdkMcpRemoteClient remote(UpstreamFixture upstream, ObjectMapper json) {
            return new SdkMcpRemoteClient(Duration.ofSeconds(2), Duration.ofSeconds(2), 65536,
                    2, 10, Set.of("127.0.0.1:" + upstream.port()), null, json, Duration.ofMillis(600));
        }
        @Bean SdkMcpGatewayToolView views(SdkMcpRemoteClient remote, Clock clock) {
            return new SdkMcpGatewayToolView(remote, clock);
        }
        @Bean McpGatewayService runtime(AgentExecutionSessionRepository sessions, NodeRunRepository nodes,
                WorkflowRunRepository workflows, ProjectRepository projects,
                McpConnectionRepository connections, ForgeInstanceIdentityRepository identity,
                McpRuntimeGrantRepository grants, SdkMcpGatewayToolView views,
                McpCredentialCipher cipher, SdkMcpRemoteClient remote, Clock clock) {
            return new McpGatewayService(sessions, nodes, workflows, projects, connections,
                    identity, grants, views, cipher, remote, clock);
        }
        @Bean McpGatewayProtocolAdapter protocol(McpGatewayService runtime,
                SdkMcpGatewayToolView views, ObjectMapper json) {
            return new McpGatewayProtocolAdapter(runtime, views, json, Duration.ofSeconds(3));
        }
        @Bean FilterRegistrationBean<McpGatewayRuntimeFilter> runtimeFilter(McpGatewayService runtime) {
            var bean = new FilterRegistrationBean<>(new McpGatewayRuntimeFilter(runtime, Set.of("localhost")));
            bean.addUrlPatterns("/*");
            return bean;
        }
    }

    @LocalServerPort int port;
    @Autowired UpstreamFixture upstream;
    @Autowired McpGatewayService runtime;
    @Autowired AgentExecutionSessionRepository sessions;
    @Autowired NodeRunRepository nodes;
    @Autowired WorkflowRunRepository workflows;
    @Autowired ProjectRepository projects;
    @Autowired McpConnectionRepository connections;
    @Autowired ForgeInstanceIdentityRepository identity;
    @Autowired McpCredentialCipher cipher;
    @Autowired SdkMcpRemoteClient remote;

    @Test void realSdkGatewayPolicyAndUpstreamAllowDenyRevokeAndNeverReplayTimeout() throws Exception {
        UUID installation = UUID.randomUUID(), sessionId = UUID.randomUUID(), turnId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID(), workflowId = UUID.randomUUID(), projectId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        Instant now = Instant.now();
        var session = new AgentExecutionSession(sessionId, workflowId, UUID.randomUUID(), UUID.randomUUID(),
                null, "codex", null, null, NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE,
                AgentExecutionSessionStatus.ACTIVE, null, nodeId, "owner", 7L, now.plusSeconds(60),
                null, null, now, now, null, null);
        var turn = new AgentExecutionTurn(turnId, sessionId, nodeId, null, 1,
                AgentExecutionTurnStatus.ACTIVE, null, null, null, null, null, now, null, now, now);
        when(sessions.findSession(sessionId)).thenReturn(Optional.of(session));
        when(sessions.findByNodeRunId(nodeId)).thenReturn(Optional.of(new AgentExecutionAllocation(session, turn)));
        when(sessions.lockCurrentLease(sessionId, "owner", 7L)).thenReturn(true);
        when(nodes.findById(nodeId)).thenReturn(Optional.of(new NodeRun(nodeId, workflowId, UUID.randomUUID(),
                UUID.randomUUID(), "agent", "instructions", null, NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(1, 1), UUID.randomUUID(), null, null, null, null,
                NodeRunStatus.RUNNING, null, null, null, now, now, null, null)));
        when(workflows.findById(workflowId)).thenReturn(Optional.of(new WorkflowRun(workflowId, projectId,
                UUID.randomUUID(), null, "workflow", "input", WorkflowRunStatus.RUNNING,
                List.of(), List.of(), List.of(), null, null, null, now, now, null, List.of())));
        when(projects.findById(projectId)).thenReturn(Optional.of(new Project(projectId, "test", "test", now, now)));
        when(identity.getOrCreate()).thenReturn(installation);
        var encrypted = new McpEncryptedCredential("synthetic", new byte[]{1, 2, 3});
        when(connections.credential(installation, connectionId)).thenReturn(Optional.of(encrypted));
        when(cipher.decrypt(installation, connectionId, "credential", encrypted))
                .thenAnswer(ignored -> SECRET.getBytes(StandardCharsets.UTF_8));
        var report = remote.probe(upstream.endpoint(), McpAuthType.BEARER,
                SECRET.getBytes(StandardCharsets.UTF_8));
        var direct = remote.call(upstream.endpoint(), McpAuthType.BEARER,
                SECRET.getBytes(StandardCharsets.UTF_8), "read",
                report.tools().getFirst().schemaFingerprint(), "{}");
        assertThat(direct.isError()).isFalse();
        assertThat(direct.contentJson()).isEqualTo("[{\"type\":\"text\",\"text\":\"ok\"}]");
        assertThat(direct.structuredContentJson()).isNull();
        upstream.resetCalls();
        var approval = new McpAllowedTool("read", report.tools().getFirst().schemaFingerprint());
        var connection = new McpConnection(connectionId, installation, "fixture", upstream.endpoint(),
                McpAuthType.BEARER, true, McpProjectAccess.all(), Set.of(approval), true,
                now, now, null, null);
        when(connections.findById(installation, connectionId)).thenReturn(Optional.of(connection));
        var claim = new AgentSessionExecutionClaim(sessionId, turnId, nodeId, "owner", 7L,
                now.plusSeconds(60), null, "codex");
        var handle = runtime.issue(claim, now.plusSeconds(50), connectionId);
        assertThat(upstream.calls()).isZero();

        var builder = HttpRequest.newBuilder().header("Authorization", "Bearer " + handle.token());
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint(path(connectionId)).requestBuilder(builder).openConnectionOnStartup(false).build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(4)).build()) {
            assertThat(client.initialize().capabilities().tools()).isNotNull();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactly("read");
            assertThat(client.callTool(new McpSchema.CallToolRequest("read", Map.of())).isError()).isFalse();
        }
        assertThat(upstream.calls()).isEqualTo(1);

        assertThat(post(connectionId, "wrong", "tools/call", "read", null).statusCode()).isEqualTo(401);
        assertThat(post(connectionId, handle.token(), "tools/call", "write", null).body())
                .contains("MCP tool unavailable");
        assertThat(post(connectionId, handle.token(), "tools/call", "read", "https://evil.example").statusCode())
                .isEqualTo(403);
        assertThat(upstream.calls()).isEqualTo(1);

        upstream.blockNextList();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var pending = worker.submit(() -> post(connectionId, handle.token(), "tools/call", "read", null));
            assertThat(upstream.listEntered().await(3, TimeUnit.SECONDS)).isTrue();
            runtime.revokeConnection(connectionId);
            upstream.releaseList();
            assertThat(pending.get(4, TimeUnit.SECONDS).body()).contains("MCP request failed");
        } finally {
            upstream.releaseList();
        }
        assertThat(upstream.calls()).isEqualTo(1);
        assertThat(post(connectionId, handle.token(), "tools/call", "read", null).statusCode()).isEqualTo(401);
        assertThat(upstream.calls()).isEqualTo(1);

        var later = runtime.issue(claim, now.plusSeconds(50), connectionId);
        upstream.blockNextCall();
        try {
            var timedOut = post(connectionId, later.token(), "tools/call", "read", null);
            assertThat(timedOut.statusCode()).isEqualTo(200);
            assertThat(timedOut.body()).contains("MCP request failed").doesNotContain(SECRET);
            assertThat(upstream.calls()).isEqualTo(2);
        } finally {
            upstream.releaseCall();
        }
        assertThat(upstream.calls()).isEqualTo(2);
    }

    private HttpResponse<String> post(UUID connectionId, String bearer, String method,
                                      String tool, String origin) throws Exception {
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method
                + "\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":{}}}";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path(connectionId)))
                .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json");
        if (origin != null) request.header("Origin", origin);
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String path(UUID connectionId) { return "/internal/mcp/connections/" + connectionId; }

    static final class UpstreamFixture implements AutoCloseable {
        private final HttpServer server;
        private final ObjectMapper json = new ObjectMapper();
        private int calls;
        private CountDownLatch release;
        private CountDownLatch listEntered;
        private CountDownLatch releaseList;

        UpstreamFixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", exchange -> {
                try {
                    if (exchange.getRequestMethod().equals("GET")) {
                        exchange.sendResponseHeaders(405, -1);
                        return;
                    }
                    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (!"Bearer ".concat(SECRET).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        exchange.sendResponseHeaders(401, -1);
                        return;
                    }
                    var root = json.readTree(request);
                    String method = root.path("method").asText();
                    if (method.equals("notifications/initialized")) {
                        exchange.sendResponseHeaders(202, -1);
                        return;
                    }
                    String id = json.writeValueAsString(root.get("id"));
                    String result;
                    if (method.equals("initialize")) {
                        result = "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
                    } else if (method.equals("tools/list")) {
                        CountDownLatch waiting;
                        synchronized (this) {
                            waiting = releaseList;
                            if (waiting != null) listEntered.countDown();
                        }
                        if (waiting != null) waiting.await(3, TimeUnit.SECONDS);
                        result = "{\"tools\":[{\"name\":\"read\",\"inputSchema\":{\"type\":\"object\"}}]}";
                    } else if (method.equals("tools/call")) {
                        CountDownLatch waiting;
                        synchronized (this) { calls++; waiting = release; }
                        if (waiting != null) waiting.await(3, TimeUnit.SECONDS);
                        result = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}";
                    } else {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) { out.write(body); }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally { exchange.close(); }
            });
            server.start();
        }

        int port() { return server.getAddress().getPort(); }
        URI endpoint() { return URI.create("http://127.0.0.1:" + port() + "/mcp"); }
        synchronized int calls() { return calls; }
        synchronized void resetCalls() { calls = 0; }
        synchronized void blockNextCall() { release = new CountDownLatch(1); }
        synchronized void releaseCall() { if (release != null) release.countDown(); release = null; }
        synchronized void blockNextList() { listEntered = new CountDownLatch(1); releaseList = new CountDownLatch(1); }
        synchronized CountDownLatch listEntered() { return listEntered; }
        synchronized void releaseList() { if (releaseList != null) releaseList.countDown(); releaseList = null; }
        @Override public void close() { releaseCall(); releaseList(); server.stop(0); }
    }
}
