package com.sitionix.forgeai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileUpdateRequest;
import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.domain.exception.KnowledgeClientException;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeActiveProfileExceptionHandlerTest {

    private KnowledgeActiveProfileExceptionHandler handler;

    @BeforeEach
    void setUp() {
        this.handler = new KnowledgeActiveProfileExceptionHandler(new ObjectMapper());
    }

    @Test
    void validUpstreamTypedErrorPreserved() {
        // given
        final KnowledgeClientException exception = new KnowledgeClientException(
                409,
                """
                        {"code":"ACTIVE_PROFILE_REVISION_CONFLICT","message":"The active profile was changed by another request","correlationId":"corr-upstream"}
                        """,
                Map.of(),
                null
        );

        // when
        final ResponseEntity<InfrastructureErrorResponse> response =
                this.handler.handleKnowledgeClientException(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "ACTIVE_PROFILE_REVISION_CONFLICT",
                "The active profile was changed by another request",
                "corr-upstream"
        ));
    }

    @Test
    void malformedUpstreamErrorBecomesBadGateway() {
        // given
        final KnowledgeClientException exception = new KnowledgeClientException(409, "{\"code\":\"ONLY_CODE\"}", Map.of(), null);

        // when
        final ResponseEntity<InfrastructureErrorResponse> response =
                this.handler.handleKnowledgeClientException(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "UPSTREAM_INVALID_RESPONSE",
                "Knowledge service returned an invalid response.",
                null
        ));
    }

    @Test
    void resourceAccessExceptionBecomesUnavailable() {
        // given
        final ResourceAccessException exception = new ResourceAccessException("connection refused");

        // when
        final ResponseEntity<InfrastructureErrorResponse> response =
                this.handler.handleResourceAccessException(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "UPSTREAM_UNAVAILABLE",
                "Knowledge service is unavailable.",
                null
        ));
    }

    @Test
    void validationBecomesBadRequest() throws NoSuchMethodException {
        // given
        final ActiveLlmProfileUpdateRequest request = new ActiveLlmProfileUpdateRequest(null, "", "", null);
        final BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(request, "request");
        bindingResult.addError(new FieldError("request", "providerId", "must not be blank"));
        final MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                this.validationMethodParameter(),
                bindingResult
        );

        // when
        final ResponseEntity<InfrastructureErrorResponse> response =
                this.handler.handleMethodArgumentNotValidException(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "VALIDATION_FAILED",
                "Active LLM profile request is invalid.",
                null
        ));
    }

    @Test
    void unreadableBodyBecomesBadRequest() {
        // given
        final HttpMessageNotReadableException exception = new HttpMessageNotReadableException("bad body");

        // when
        final ResponseEntity<InfrastructureErrorResponse> response =
                this.handler.handleHttpMessageNotReadableException(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(new InfrastructureErrorResponse(
                "VALIDATION_FAILED",
                "Request body is invalid or does not match the expected contract.",
                null
        ));
    }

    @Test
    void handlerOrderIsHighestPrecedence() {
        // given
        final Order order = KnowledgeActiveProfileExceptionHandler.class.getAnnotation(Order.class);

        // when
        final int value = order.value();

        // then
        assertThat(value).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    private MethodParameter validationMethodParameter() throws NoSuchMethodException {
        final Method method = KnowledgeActiveProfileExceptionHandlerTest.class.getDeclaredMethod(
                "validationTarget",
                ActiveLlmProfileUpdateRequest.class
        );
        return new MethodParameter(method, 0);
    }

    @SuppressWarnings("unused")
    private void validationTarget(final ActiveLlmProfileUpdateRequest request) {
    }
}
