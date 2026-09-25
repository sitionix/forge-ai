package com.sitionix.forgeagent.infrastructure.local.mcp.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.port.McpRemoteProbe;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "forge.mcp.enabled", havingValue = "true")
class McpProbeConfiguration {
    private static final Duration MAX_MANAGEMENT_PROBE_BUDGET = Duration.ofSeconds(25);
    @Bean McpRemoteProbe mcpRemoteProbe(
            @Value("${forge.mcp.probe.connect-timeout:2s}") Duration connectTimeout,
            @Value("${forge.mcp.probe.request-timeout:3s}") Duration requestTimeout,
            @Value("${forge.mcp.probe.max-response-bytes:1048576}") int maxResponseBytes,
            @Value("${forge.mcp.probe.max-pages:5}") int maxPages,
            @Value("${forge.mcp.probe.max-tools:500}") int maxTools,
            @Value("${forge.mcp.probe.allowed-private-endpoints:}") String allowedPrivateEndpoints,
            @Value("${forge.mcp.probe.ssl-bundle:}") String sslBundle,
            ObjectProvider<SslBundles> bundles, ObjectMapper objectMapper) {
        if (maxPages < 1 || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || requestTimeout.multipliedBy((long) maxPages + 2).compareTo(MAX_MANAGEMENT_PROBE_BUDGET) > 0)
            throw new IllegalArgumentException("MCP probe duration exceeds the Nexus management HTTP budget");
        Set<String> allowances = Arrays.stream(allowedPrivateEndpoints.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        javax.net.ssl.SSLContext context = null;
        if (!sslBundle.isBlank()) {
            SslBundles available = bundles.getIfAvailable();
            if (available == null) throw new IllegalStateException("MCP SSL bundle is unavailable");
            context = available.getBundle(sslBundle).createSslContext();
        }
        return new SdkMcpRemoteProbe(connectTimeout, requestTimeout, maxResponseBytes,
                maxPages, maxTools, allowances, context, objectMapper);
    }
}
