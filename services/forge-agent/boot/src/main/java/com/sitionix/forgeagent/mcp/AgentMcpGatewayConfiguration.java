package com.sitionix.forgeagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.mcp.McpGatewayService;
import com.sitionix.forgeagent.application.mcp.McpExecutionSelectionService;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.infrastructure.local.mcp.gateway.InMemoryMcpRuntimeGrantRepository;
import com.sitionix.forgeagent.infrastructure.local.mcp.gateway.SdkMcpGatewayToolView;
import com.sitionix.forgeagent.infrastructure.local.mcp.protocol.SdkMcpRemoteClient;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.Duration;
import java.net.URI;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "forge.mcp.enabled", havingValue = "true")
class AgentMcpGatewayConfiguration {
    @Bean McpGatewayAddress mcpGatewayAddress(ServletWebServerApplicationContext context) {
        return () -> {
            if (context.getWebServer() == null || context.getWebServer().getPort() <= 0)
                throw new IllegalStateException("MCP gateway connector is unavailable");
            return URI.create("http://127.0.0.1:" + context.getWebServer().getPort());
        };
    }

    @Bean McpRuntimeGrantRepository mcpRuntimeGrantRepository(Clock clock,
            @Value("${forge.mcp.gateway.max-grants:1000}") int capacity) {
        return new InMemoryMcpRuntimeGrantRepository(clock, System::nanoTime, capacity);
    }

    @Bean SdkMcpGatewayToolView sdkMcpGatewayToolView(SdkMcpRemoteClient remote, Clock clock) {
        return new SdkMcpGatewayToolView(remote, clock);
    }

    @Bean McpGatewayService mcpGatewayService(AgentExecutionSessionRepository sessions,
            NodeRunRepository nodes, WorkflowRunRepository workflows, ProjectRepository projects,
            McpConnectionRepository connections, ForgeInstanceIdentityRepository identity,
            McpRuntimeGrantRepository grants, SdkMcpGatewayToolView views,
            McpCredentialCipher cipher, McpRemoteToolClient remote, Clock clock) {
        return new McpGatewayService(sessions, nodes, workflows, projects, connections, identity,
                grants, views, cipher, remote, clock);
    }

    @Bean McpExecutionSelectionService mcpExecutionSelectionService(WorkflowRunRepository workflows,
            McpConnectionRepository connections, ForgeInstanceIdentityRepository identity,
            McpGatewayService gateway) {
        return new McpExecutionSelectionService(workflows, connections, identity, gateway);
    }

    @Bean McpGatewayProtocolAdapter mcpGatewayProtocolAdapter(McpGatewayService runtime,
            SdkMcpGatewayToolView views, ObjectMapper json,
            @Value("${forge.mcp.tool-call-timeout:30s}") Duration timeout) {
        return new McpGatewayProtocolAdapter(runtime, views, json, timeout.plusSeconds(10));
    }

    @Bean FilterRegistrationBean<McpGatewayRuntimeFilter> mcpGatewayRuntimeGuard(McpGatewayService runtime,
            @Value("${forge.mcp.gateway.allowed-hosts:localhost,127.0.0.1}") String allowedHosts) {
        Set<String> hosts = Arrays.stream(allowedHosts.split(","))
                .map(value -> value.strip().toLowerCase(Locale.ROOT)).filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        var registration = new FilterRegistrationBean<>(new McpGatewayRuntimeFilter(runtime, hosts));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC,
                DispatcherType.ERROR, DispatcherType.FORWARD, DispatcherType.INCLUDE));
        registration.addUrlPatterns("/*");
        registration.setAsyncSupported(true);
        registration.setOrder(Integer.MIN_VALUE + 1);
        return registration;
    }
}
