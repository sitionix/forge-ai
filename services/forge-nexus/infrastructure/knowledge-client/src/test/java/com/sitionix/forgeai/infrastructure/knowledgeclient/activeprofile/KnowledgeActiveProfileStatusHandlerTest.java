package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileFailureReason;
import com.sitionix.forgeai.domain.port.CorrelationIdProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class KnowledgeActiveProfileStatusHandlerTest {

    private KnowledgeActiveProfileStatusHandler handler;

    @BeforeEach
    void setUp() {
        final ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.handler = new KnowledgeActiveProfileStatusHandler(objectMapper, () -> "corr-local");
    }

    @Test
    void revisionConflictPreservesControlledError() {
        // given
        final ClientHttpResponse response = response(HttpStatus.CONFLICT, """
                {"code":"ACTIVE_PROFILE_REVISION_CONFLICT","message":"The active profile was changed by another request","correlationId":"corr-409"}
                """);

        // when // then
        assertThatThrownBy(() -> this.handler.handle(mock(HttpRequest.class), response))
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(KnowledgeActiveProfileFailureReason.CONFLICT);
                    assertThat(exception.code()).isEqualTo("ACTIVE_PROFILE_REVISION_CONFLICT");
                    assertThat(exception.correlationId()).isEqualTo("corr-409");
                });
    }

    @Test
    void malformedControlledErrorMapsToInvalidResponse() {
        // given
        final ClientHttpResponse response = response(HttpStatus.CONFLICT, "{\"code\":\"ACTIVE_PROFILE_REVISION_CONFLICT\"");

        // when // then
        assertThatThrownBy(() -> this.handler.handle(mock(HttpRequest.class), response))
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(KnowledgeActiveProfileFailureReason.INVALID_RESPONSE);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
                });
    }

    @Test
    void unexpectedServerErrorMapsToUpstreamFailure() {
        // given
        final ClientHttpResponse response = response(HttpStatus.INTERNAL_SERVER_ERROR, "internal");

        // when // then
        assertThatThrownBy(() -> this.handler.handle(mock(HttpRequest.class), response))
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(KnowledgeActiveProfileFailureReason.UPSTREAM_FAILURE);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_ERROR");
                });
    }

    @Test
    void redirectMapsToInvalidResponse() {
        // given
        final ClientHttpResponse response = response(HttpStatus.FOUND, "");

        // when // then
        assertThatThrownBy(() -> this.handler.handle(mock(HttpRequest.class), response))
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(KnowledgeActiveProfileFailureReason.INVALID_RESPONSE);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
                });
    }

    private static ClientHttpResponse response(final HttpStatus status, final String body) {
        return new ClientHttpResponse() {
            @Override
            public HttpStatusCode getStatusCode() {
                return status;
            }

            @Override
            public String getStatusText() {
                return status.getReasonPhrase();
            }

            @Override
            public void close() {
            }

            @Override
            public ByteArrayInputStream getBody() {
                return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
    }
}
