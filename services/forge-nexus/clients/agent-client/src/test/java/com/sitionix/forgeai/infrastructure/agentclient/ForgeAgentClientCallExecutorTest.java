package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeai.domain.exception.AgentClientException;
import com.sitionix.forgeai.domain.exception.McpAgentClientException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

class ForgeAgentClientCallExecutorTest {

    @Test
    void mcpPreservesParsedErrorAcrossStatusesWithoutRawTransportGraph() {
        final var executor = new ForgeAgentClientCallExecutor(this.properties(true));
        for (int status : new int[] {400, 404, 409, 422, 500}) {
            final var headers = new HttpHeaders();
            headers.add("X-Secret", "header-canary");
            assertThatThrownBy(() -> executor.executeMcp(() -> {
                throw raw(status, "{\"code\":\"MCP_OPERATION_FAILED\",\"message\":\"MCP management operation failed.\",\"correlationId\":\"corr-1\"}", headers);
            })).isInstanceOfSatisfying(McpAgentClientException.class, error -> {
                assertThat(error.statusCode()).isEqualTo(status);
                assertThat(error.code()).isEqualTo("MCP_OPERATION_FAILED");
                assertThat(error.upstreamMessage()).isEqualTo("MCP management operation failed.");
                assertThat(error.correlationId()).isEqualTo("corr-1");
                safeGraph(error);
            });
        }
    }

    @Test
    void malformedBodyAndExtraFieldsBecomeSafe502() {
        final var executor = new ForgeAgentClientCallExecutor(this.properties(true));
        for (String body : new String[] {"body-canary", "{\"code\":\"X\"}",
                "{\"code\":\"X\",\"message\":\"safe\",\"secret\":\"body-canary\"}",
                "{\"code\":\"X\",\"message\":\"safe\"} body-canary",
                "{\"code\":\"X\",\"message\":\"safe\"}{\"secret\":\"body-canary\"}",
                "{\"code\":\"X\",\"message\":\"safe\",\"message\":\"body-canary\"}"}) {
            assertThatThrownBy(() -> executor.executeMcp(() -> { throw raw(500, body, new HttpHeaders()); }))
                .isInstanceOfSatisfying(McpAgentClientException.class, error -> {
                    assertThat(error.statusCode()).isEqualTo(502);
                    assertThat(error.code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
                    safeGraph(error);
                });
        }
    }

    @Test
    void transportAndDecoderFailuresStayAtClientBoundary() {
        final var executor = new ForgeAgentClientCallExecutor(this.properties(true));
        for (RuntimeException raw : new RuntimeException[] {new ResourceAccessException("transport-canary"),
                new RestClientException("decode-canary"), new IllegalStateException("decode-canary")}) {
            assertThatThrownBy(() -> executor.executeMcp(() -> { throw raw; }))
                .isInstanceOfSatisfying(McpAgentClientException.class, error -> {
                    assertThat(error.statusCode()).isEqualTo(raw instanceof ResourceAccessException ? 503 : 502);
                    safeGraph(error);
                });
        }
    }

    private RestClientResponseException raw(int status, String body, HttpHeaders headers) {
        return new RestClientResponseException("cause-canary", status, "failure", headers,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private void safeGraph(Throwable error) {
        assertThat(error.getCause()).isNull();
        assertThat(error.getSuppressed()).isEmpty();
        assertThat(error.getMessage() + error.toString() + java.util.Arrays.toString(error.getStackTrace()))
            .doesNotContain("body-canary", "header-canary", "cause-canary", "transport-canary", "decode-canary");
    }

    @Test
    void executesEnabledCall() {
        final var executor = new ForgeAgentClientCallExecutor(this.properties(true));

        final String actual = executor.execute(() -> "ok");

        assertThat(actual).isEqualTo("ok");
    }

    @Test
    void disabledClientIsUnavailable() {
        final var executor = new ForgeAgentClientCallExecutor(this.properties(false));

        assertThatThrownBy(() -> executor.execute(() -> "ok"))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void upstreamErrorIsPreservedForScopedAdvice() {
        final var executor = new ForgeAgentClientCallExecutor(this.properties(true));
        final var headers = new HttpHeaders();
        headers.add("X-Correlation-Id", "corr-1");

        assertThatThrownBy(() -> executor.execute(() -> {
            throw new RestClientResponseException(
                    "Conflict",
                    HttpStatus.CONFLICT.value(),
                    "Conflict",
                    headers,
                    "{\"code\":\"DEPENDENCY_CYCLE\",\"message\":\"cycle\"}".getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8
            );
        }))
                .isInstanceOfSatisfying(AgentClientException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
                    assertThat(exception.responseBody()).contains("DEPENDENCY_CYCLE");
                    assertThat(exception.responseHeaders()).containsKey("X-Correlation-Id");
                });
    }

    private ForgeAgentClientProperties properties(final boolean enabled) {
        final var properties = new ForgeAgentClientProperties();
        properties.setEnabled(enabled);
        properties.setBaseUrl(URI.create("http://forge-agent.test"));
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));
        return properties;
    }
}
