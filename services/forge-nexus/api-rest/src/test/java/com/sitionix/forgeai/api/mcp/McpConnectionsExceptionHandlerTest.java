package com.sitionix.forgeai.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.exception.AgentClientException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpConnectionsExceptionHandlerTest {
    private final McpConnectionsExceptionHandler handler = new McpConnectionsExceptionHandler(new ObjectMapper());

    @Test
    void validUpstreamErrorsKeepEveryStatusAndOptionalCorrelationId() {
        for (int status : new int[]{400, 404, 409, 422, 500}) {
            for (String correlation : new String[]{"\"correlationId\":\"corr-1\",", ""}) {
                var response = handler.upstream(raw(status,
                        "{" + correlation + "\"code\":\"MCP_OPERATION_FAILED\",\"message\":\"MCP management operation failed.\"}"));
                assertThat(response.getStatusCode().value()).isEqualTo(status);
                assertThat(response.getBody().code()).isEqualTo("MCP_OPERATION_FAILED");
                assertThat(response.getBody().message()).isEqualTo("MCP management operation failed.");
                assertThat(response.getBody().correlationId()).isEqualTo(correlation.isEmpty() ? null : "corr-1");
            }
        }
    }

    @Test
    void malformedErrorsAreStatic502WithoutRawTransportFields() {
        for (String body : new String[]{"body-canary", "", "{\"code\":\"X\"}",
                "{\"code\":\"X\",\"message\":\"safe\",\"secret\":\"body-canary\"}",
                "{\"code\":\"X\",\"message\":\"safe\"} body-canary",
                "{\"code\":\"X\",\"message\":\"safe\"}{\"secret\":\"body-canary\"}",
                "{\"code\":\"X\",\"message\":\"safe\",\"message\":\"body-canary\"}",
                "{\"code\":5,\"message\":\"safe\"}",
                "{\"code\":\"X\",\"message\":\" \"}"}) {
            var response = handler.upstream(raw(500, body));
            assertThat(response.getStatusCode().value()).isEqualTo(502);
            assertThat(response.getBody().code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
            assertThat(response.getBody().toString()).doesNotContain("body-canary", "header-canary", "cause-canary");
        }
        assertThat(handler.upstream(raw(200, "{\"code\":\"X\",\"message\":\"safe\"}"))
                .getStatusCode().value()).isEqualTo(502);
    }

    @Test
    void unavailableAndLocalErrorsUseMcpPublicShape() {
        assertThat(handler.unavailable().getStatusCode().value()).isEqualTo(503);
        assertThat(handler.unavailable().getBody().code()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(handler.invalid().getStatusCode().value()).isEqualTo(400);
        assertThat(handler.invalid().getBody().code()).isEqualTo("INVALID_REQUEST");
        assertThat(handler.missing().getStatusCode().value()).isEqualTo(404);
        assertThat(handler.missing().getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(handler.failed().getStatusCode().value()).isEqualTo(502);
    }

    private static AgentClientException raw(int status, String body) {
        return new AgentClientException(status, body, Map.of("X-Secret", List.of("header-canary")),
                new IllegalStateException("cause-canary"));
    }
}
