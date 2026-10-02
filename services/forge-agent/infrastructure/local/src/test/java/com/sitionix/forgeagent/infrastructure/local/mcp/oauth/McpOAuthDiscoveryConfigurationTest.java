package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
class McpOAuthDiscoveryConfigurationTest {
    @Test void discoveryBudgetMustFitExistingManagementTransport(){
        for(var duration:new Duration[]{Duration.ZERO,Duration.ofSeconds(-1),Duration.ofSeconds(26)})
            assertThatThrownBy(()->new McpOAuthDiscoveryProperties(duration)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new McpOAuthDiscoveryProperties(Duration.ofSeconds(20)).discoveryTimeout()).isEqualTo(Duration.ofSeconds(20));
    }
}
