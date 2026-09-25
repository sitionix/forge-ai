package com.sitionix.forgeagent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.McpAllowedTool;
import com.sitionix.forgeagent.domain.model.McpAuthType;
import com.sitionix.forgeagent.domain.model.McpRuntimeGrant;
import com.sitionix.forgeagent.domain.model.McpToolCallResult;
import com.sitionix.forgeagent.domain.port.McpGatewayRuntime;
import com.sitionix.forgeagent.infrastructure.local.mcp.gateway.SdkMcpGatewayToolView;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Real Codex native tool calls through the production Spring MCP gateway HTTP endpoint. */
@EnabledIfSystemProperty(named = "forge.codex.stage4-e2e", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = McpCodexRuntimeEndToEndHttpTest.TestApp.class, properties = "forge.mcp.enabled=true")
class McpCodexRuntimeEndToEndHttpTest {
    private static final UUID CONNECTION = UUID.fromString("01234567-89ab-4cde-8012-3456789abcde");
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class,
            MongoAutoConfiguration.class, MongoDataAutoConfiguration.class})
    @Import(McpGatewayController.class)
    static class TestApp {
        @Bean McpGatewayRuntime runtime() { return mock(McpGatewayRuntime.class); }
        @Bean SdkMcpGatewayToolView views() { return mock(SdkMcpGatewayToolView.class); }
        @Bean McpGatewayProtocolAdapter protocol(McpGatewayRuntime runtime,
                SdkMcpGatewayToolView views, ObjectMapper json) {
            return new McpGatewayProtocolAdapter(runtime, views, json, Duration.ofSeconds(5));
        }
        @Bean FilterRegistrationBean<McpGatewayRuntimeFilter> runtimeFilter(McpGatewayRuntime runtime) {
            var registration = new FilterRegistrationBean<>(
                    new McpGatewayRuntimeFilter(runtime, Set.of("localhost", "127.0.0.1")));
            registration.addUrlPatterns("/*");
            return registration;
        }
    }

    @LocalServerPort int port;
    @Autowired McpGatewayRuntime runtime;
    @Autowired SdkMcpGatewayToolView views;
    @Autowired ObjectMapper json;

    @Test void realCodexCallsForgeGatewayOnFreshAndResumeWithDistinctGrants() throws Exception {
        reset(runtime, views);
        McpAllowedTool approval = new McpAllowedTool("echo", FINGERPRINT);
        McpRuntimeGrant first = grant(approval);
        McpRuntimeGrant second = grant(approval);
        when(runtime.authorize("synthetic-stage4-grant-a", CONNECTION)).thenReturn(first);
        when(runtime.authorize("synthetic-stage4-grant-b", CONNECTION)).thenReturn(second);
        var tool = McpSchema.Tool.builder().name("echo")
                .inputSchema(new JacksonMcpJsonMapper(json),
                        "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}").build();
        when(views.tools(first.id())).thenReturn(List.of(tool));
        when(views.tools(second.id())).thenReturn(List.of(tool));
        when(runtime.call("synthetic-stage4-grant-a", CONNECTION, "echo", FINGERPRINT,
                "{\"text\":\"read-only\"}"))
                .thenReturn(new McpToolCallResult(false, "[{\"type\":\"text\",\"text\":\"fixture:read-only\"}]", null));
        when(runtime.call("synthetic-stage4-grant-b", CONNECTION, "echo", FINGERPRINT,
                "{\"text\":\"read-only\"}"))
                .thenReturn(new McpToolCallResult(false, "[{\"type\":\"text\",\"text\":\"fixture:read-only\"}]", null));

        ProcessBuilder builder = new ProcessBuilder("python3", fixture().toString()).redirectErrorStream(true);
        builder.environment().put("FORGE_STAGE4_GATEWAY_BASE", "http://localhost:" + port);
        Process process = builder.start();
        boolean completed = process.waitFor(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(completed).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("STAGE4_NATIVE_ASSERTION PASS")
                .doesNotContain("synthetic-stage4-grant-a", "synthetic-stage4-grant-b");
        verify(runtime, atLeastOnce()).call("synthetic-stage4-grant-a", CONNECTION, "echo", FINGERPRINT, "{\"text\":\"read-only\"}");
        verify(runtime, atLeastOnce()).call("synthetic-stage4-grant-b", CONNECTION, "echo", FINGERPRINT, "{\"text\":\"read-only\"}");
    }

    private static McpRuntimeGrant grant(McpAllowedTool tool) {
        return new McpRuntimeGrant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "owner", 1L, CONNECTION,
                URI.create("http://127.0.0.1/synthetic"), McpAuthType.NONE, "none", Set.of(tool),
                Instant.now().plusSeconds(180));
    }

    private static Path fixture() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null;
                directory = directory.getParent()) {
            Path script = directory.resolve("scripts/runtime/tests/stage4_codex_fixture.py");
            if (Files.isRegularFile(script)) return script;
        }
        throw new IllegalStateException("Stage 4 Codex fixture is unavailable");
    }
}
