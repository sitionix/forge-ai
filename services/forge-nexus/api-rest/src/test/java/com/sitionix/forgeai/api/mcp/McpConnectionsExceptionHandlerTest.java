package com.sitionix.forgeai.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeai.domain.exception.McpAgentClientException;
import org.junit.jupiter.api.Test;

class McpConnectionsExceptionHandlerTest {
    @Test
    void agentOperationFailureKeepsHttp500() {
        var response = new McpConnectionsExceptionHandler().upstream(
                new McpAgentClientException(500, "MCP_OPERATION_FAILED",
                        "MCP management operation failed.", "corr-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().code()).isEqualTo("MCP_OPERATION_FAILED");
        assertThat(response.getBody().message()).isEqualTo("MCP management operation failed.");
        assertThat(response.getBody().correlationId()).isEqualTo("corr-1");
    }
}
