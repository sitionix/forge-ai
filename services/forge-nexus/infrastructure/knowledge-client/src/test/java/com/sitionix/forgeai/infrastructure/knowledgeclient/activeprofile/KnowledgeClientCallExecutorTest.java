package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.exception.KnowledgeClientException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeClientCallExecutorTest {

    private KnowledgeClientCallExecutor executor;

    @BeforeEach
    void setUp() {
        this.executor = new KnowledgeClientCallExecutor(this.properties(true));
    }

    @Test
    void successfulSupplierResultReturned() {
        // given
        final String expected = "ok";

        // when
        final String actual = this.executor.execute(() -> expected);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void restClientResponseExceptionConvertedToKnowledgeClientException() {
        // given
        final HttpHeaders headers = new HttpHeaders();
        headers.add("X-Upstream", "value");
        final RestClientResponseException upstream = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                headers,
                "{\"code\":\"RATE_LIMITED\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );

        // when / then
        assertThatThrownBy(() -> this.executor.execute(() -> {
            throw upstream;
        })).isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
            assertThat(exception.statusCode()).isEqualTo(429);
            assertThat(exception.responseBody()).isEqualTo("{\"code\":\"RATE_LIMITED\"}");
            assertThat(exception.responseHeaders()).containsEntry("X-Upstream", List.of("value"));
            assertThat(exception.getCause()).isSameAs(upstream);
        });
    }

    @Test
    void disabledPropertyThrowsResourceAccessExceptionWithoutExecutingSupplier() {
        // given
        final KnowledgeClientCallExecutor disabledExecutor = new KnowledgeClientCallExecutor(this.properties(false));
        final AtomicBoolean executed = new AtomicBoolean(false);

        // when / then
        assertThatThrownBy(() -> disabledExecutor.execute(() -> {
            executed.set(true);
            return "ok";
        })).isInstanceOf(ResourceAccessException.class);
        assertThat(executed).isFalse();
    }

    private KnowledgeActiveProfileClientProperties properties(final boolean enabled) {
        final KnowledgeActiveProfileClientProperties properties = new KnowledgeActiveProfileClientProperties();
        properties.setEnabled(enabled);
        return properties;
    }
}
