package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.exception.KnowledgeClientException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeClientCallExecutorTest {

    private KnowledgeClientCallExecutor executor;

    @BeforeEach
    void setUp() {
        this.executor = new KnowledgeClientCallExecutor();
    }

    @Test
    void successfulSupplierResultReturned() {
        assertThat(this.executor.execute(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void restClientResponseExceptionConvertedToKnowledgeClientException() {
        final HttpHeaders headers = new HttpHeaders();
        headers.add("X-Upstream", "value");
        final RestClientResponseException upstream = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                headers,
                "{\"code\":\"RATE_LIMITED\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );

        assertThatThrownBy(() -> this.executor.execute(() -> {
            throw upstream;
        })).isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
            assertThat(exception.statusCode()).isEqualTo(429);
            assertThat(exception.responseBody()).isEqualTo("{\"code\":\"RATE_LIMITED\"}");
            assertThat(exception.responseHeaders()).containsEntry("X-Upstream", java.util.List.of("value"));
            assertThat(exception.getCause()).isSameAs(upstream);
        });
    }
}
