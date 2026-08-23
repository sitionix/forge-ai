package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.domain.exception.AgentClientException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

class ForgeAiInfrastructureAgentsExceptionHandlerTest {

    private ForgeAiInfrastructureAgentsExceptionHandler handler;

    @BeforeEach
    void setUp() {
        this.handler = new ForgeAiInfrastructureAgentsExceptionHandler(new ObjectMapper());
    }

    @Test
    void validUpstreamTypedErrorPreserved() {
        final var exception = new AgentClientException(
                409,
                """
                        {"code":"DEPENDENCY_CYCLE","message":"Dependency graph contains a cycle.","correlationId":"corr-upstream"}
                        """,
                Map.of(),
                null
        );

        final var response = this.handler.handleAgentClientException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "DEPENDENCY_CYCLE",
                "Dependency graph contains a cycle.",
                "corr-upstream"
        ));
    }

    @Test
    void malformedForgeAgentErrorBodyBecomesBadGateway() {
        final var exception = new AgentClientException(409, "{\"code\":\"ONLY_CODE\"}", Map.of(), null);

        final var response = this.handler.handleAgentClientException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "UPSTREAM_INVALID_RESPONSE",
                "Forge Agent service returned an invalid response.",
                null
        ));
    }

    @Test
    void unavailableForgeAgentBecomesServiceUnavailable() {
        final var response = this.handler.handleResourceAccessException(new ResourceAccessException("connection refused"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "UPSTREAM_UNAVAILABLE",
                "Forge Agent service is unavailable.",
                null
        ));
    }

    @Test
    void invalidSuccessfulForgeAgentResponseBecomesBadGateway() {
        final var response = this.handler.handleInvalidUpstreamResponse(new HttpMessageConversionException("bad upstream"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "UPSTREAM_INVALID_RESPONSE",
                "Forge Agent service returned an invalid response.",
                null
        ));
    }

    @Test
    void restClientProtocolErrorBecomesBadGateway() {
        final var response = this.handler.handleInvalidUpstreamResponse(new RestClientException("bad upstream"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "UPSTREAM_INVALID_RESPONSE",
                "Forge Agent service returned an invalid response.",
                null
        ));
    }

    @Test
    void malformedRequestBodyBecomesBadRequest() {
        final var response = this.handler.handleBadRequest(new HttpMessageNotReadableException("malformed JSON"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "VALIDATION_FAILED",
                "Agent request is invalid.",
                null
        ));
    }

    @Test
    void handlerOrderIsHighestPrecedence() {
        final Order order = ForgeAiInfrastructureAgentsExceptionHandler.class.getAnnotation(Order.class);

        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
