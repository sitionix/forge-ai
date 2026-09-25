package com.sitionix.forgeagent.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.domain.exception.McpProbeException;
import org.junit.jupiter.api.Test;

class McpProbeErrorHandlerTest {
    private final McpConnectionsExceptionHandler handler = new McpConnectionsExceptionHandler();

    @Test void mapsProbeReasonsToSafePublicErrors() {
        for (var scenario : new Object[][] {
                {McpProbeException.Reason.AUTH_REQUIRED, 401, "MCP_AUTH_REQUIRED"},
                {McpProbeException.Reason.FORBIDDEN, 403, "MCP_FORBIDDEN"},
                {McpProbeException.Reason.UNSUPPORTED_PROTOCOL, 502, "MCP_UNSUPPORTED_PROTOCOL"},
                {McpProbeException.Reason.INVALID_RESPONSE, 502, "MCP_INVALID_RESPONSE"},
                {McpProbeException.Reason.UNAVAILABLE, 503, "MCP_UNAVAILABLE"},
                {McpProbeException.Reason.ENDPOINT_DENIED, 400, "MCP_ENDPOINT_DENIED"}
        }) {
            var response = handler.probeFailure(new McpProbeException((McpProbeException.Reason) scenario[0]));
            assertThat(response.getStatusCode().value()).isEqualTo(scenario[1]);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo(scenario[2]);
            assertThat(response.getBody().message()).doesNotContain("credential", "token", "Authorization");
        }
    }
}
