package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeai.domain.exception.AgentClientException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class ForgeAgentClientCallExecutorTest {

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
