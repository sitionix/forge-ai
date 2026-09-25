package com.sitionix.forgeagent.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class McpAvailableErrorHandlerTest {
    @Test
    void registryUnavailableHasSafeFeatureError() {
        var response = new McpConnectionsExceptionHandler().registryUnavailable();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("MCP_REGISTRY_UNAVAILABLE");
        assertThat(response.getBody().message()).isEqualTo("MCP Registry is unavailable.");
    }
}
