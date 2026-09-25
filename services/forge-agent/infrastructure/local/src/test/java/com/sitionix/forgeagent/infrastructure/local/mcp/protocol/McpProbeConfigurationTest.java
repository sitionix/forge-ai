package com.sitionix.forgeagent.infrastructure.local.mcp.protocol;

import static org.assertj.core.api.Assertions.*;

import com.sitionix.forgeagent.domain.port.McpRemoteProbe;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class McpProbeConfigurationTest {
    @Test void defaultConfigurationCreatesBoundedProbeWithoutPrivateAllowance() {
        try (var context = context(Map.of("forge.mcp.enabled", "true"))) {
            assertThat(context.getBean(McpRemoteProbe.class)).isInstanceOf(SdkMcpRemoteProbe.class);
        }
    }

    @Test void oversizedBudgetFailsStartupInsteadOfOutlivingNexusRequest() {
        assertThatThrownBy(() -> context(Map.of("forge.mcp.enabled", "true",
                "forge.mcp.probe.request-timeout", "10s", "forge.mcp.probe.max-pages", "5")))
                .hasRootCauseMessage("MCP probe duration exceeds the Nexus management HTTP budget");
    }

    private AnnotationConfigApplicationContext context(Map<String, String> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", new java.util.HashMap<String, Object>(properties)));
        context.registerBean(com.fasterxml.jackson.databind.ObjectMapper.class, () -> new com.fasterxml.jackson.databind.ObjectMapper());
        context.register(McpProbeConfiguration.class);
        try { context.refresh(); }
        catch (RuntimeException failure) { context.close(); throw failure; }
        return context;
    }
}
