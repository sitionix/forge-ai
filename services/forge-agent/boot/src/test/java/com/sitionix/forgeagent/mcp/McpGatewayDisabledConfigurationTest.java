package com.sitionix.forgeagent.mcp;

import static org.assertj.core.api.Assertions.*;

import com.sitionix.forgeagent.domain.port.McpRuntimeGrantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class McpGatewayDisabledConfigurationTest {
    @Test void ordinaryAgentDoesNotRequireRuntimeGatewayConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentMcpGatewayConfiguration.class, McpGatewayController.class)
                .withPropertyValues("forge.mcp.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(McpRuntimeGrantRepository.class);
                    assertThat(context).doesNotHaveBean(McpGatewayRuntimeFilter.class);
                    assertThat(context).doesNotHaveBean(McpGatewayController.class);
                });
    }
}
