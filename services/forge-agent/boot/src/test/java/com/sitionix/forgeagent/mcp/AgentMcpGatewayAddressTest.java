package com.sitionix.forgeagent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;

class AgentMcpGatewayAddressTest {
    @Test void usesTheActualServletConnectorPort() {
        var context = mock(ServletWebServerApplicationContext.class);
        var server = mock(WebServer.class);
        when(context.getWebServer()).thenReturn(server);
        when(server.getPort()).thenReturn(18432);

        assertThat(new AgentMcpGatewayConfiguration().mcpGatewayAddress(context).baseUrl().toString())
                .isEqualTo("http://127.0.0.1:18432");
    }

    @Test void mockWebContextCannotAdvertiseAnUnboundGateway() {
        var context = mock(ApplicationContext.class);
        assertThatThrownBy(() -> new AgentMcpGatewayConfiguration().mcpGatewayAddress(context).baseUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP gateway connector is unavailable");
    }
}
