package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.domain.exception.KnowledgeClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeActiveProfileExceptionHandlerTest {

    private MockHttpServletRequest request;
    private KnowledgeClientExceptionHandler handler;

    @BeforeEach
    void setUp() {
        this.request = new MockHttpServletRequest();
        this.request.setAttribute(NexusCorrelationFilter.CORRELATION_ATTRIBUTE, "corr-local");
        this.handler = new KnowledgeClientExceptionHandler(this.request);
    }

    @Test
    void conflictPreservesControlledError() {
        // given
        final KnowledgeClientException exception = new KnowledgeClientException(
                409,
                "ACTIVE_PROFILE_REVISION_CONFLICT",
                "The active profile was changed by another request",
                "corr-upstream",
                null
        );

        // when
        final ResponseEntity<InfrastructureErrorResponse> response = this.handler.handle(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-upstream");
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "ACTIVE_PROFILE_REVISION_CONFLICT",
                "The active profile was changed by another request",
                "corr-upstream"
        ));
    }

    @Test
    void invalidUpstreamCorrelationFallsBackToStableLocalCorrelation() {
        // given
        final KnowledgeClientException exception = new KnowledgeClientException(
                503,
                "UPSTREAM_UNAVAILABLE",
                "Knowledge service is unavailable.",
                "bad header value",
                null
        );

        // when
        final ResponseEntity<InfrastructureErrorResponse> response = this.handler.handle(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-local");
        assertThat(response.getBody().correlationId()).isEqualTo("corr-local");
    }
}
