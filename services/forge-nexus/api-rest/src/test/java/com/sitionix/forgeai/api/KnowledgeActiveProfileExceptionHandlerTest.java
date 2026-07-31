package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileFailureReason;
import com.sitionix.forgeai.domain.port.CorrelationIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeActiveProfileExceptionHandlerTest {

    private KnowledgeActiveProfileExceptionHandler handler;

    @BeforeEach
    void setUp() {
        this.handler = new KnowledgeActiveProfileExceptionHandler(() -> "corr-local");
    }

    @Test
    void conflictPreservesControlledError() {
        // given
        final KnowledgeActiveProfileClientException exception = new KnowledgeActiveProfileClientException(
                KnowledgeActiveProfileFailureReason.CONFLICT,
                "ACTIVE_PROFILE_REVISION_CONFLICT",
                "The active profile was changed by another request",
                "corr-upstream"
        );

        // when
        final ResponseEntity<InfrastructureErrorResponse> response = this.handler.handleKnowledgeActiveProfileClientException(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst(CorrelationIdProvider.HEADER_NAME)).isEqualTo("corr-upstream");
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "ACTIVE_PROFILE_REVISION_CONFLICT",
                "The active profile was changed by another request",
                "corr-upstream"
        ));
    }

    @Test
    void invalidUpstreamCorrelationFallsBackToStableLocalCorrelation() {
        // given
        final KnowledgeActiveProfileClientException exception = new KnowledgeActiveProfileClientException(
                KnowledgeActiveProfileFailureReason.UNAVAILABLE,
                "UPSTREAM_UNAVAILABLE",
                "Knowledge service is unavailable.",
                "bad header value"
        );

        // when
        final ResponseEntity<InfrastructureErrorResponse> response = this.handler.handleKnowledgeActiveProfileClientException(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(CorrelationIdProvider.HEADER_NAME)).isEqualTo("corr-local");
        assertThat(response.getBody().correlationId()).isEqualTo("corr-local");
    }
}
