package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileFailureReason;
import com.sitionix.forgeai.domain.port.CorrelationIdProvider;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeErrorResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

@RequiredArgsConstructor
final class KnowledgeActiveProfileStatusHandler {

    private static final String UPSTREAM_INVALID_RESPONSE = "UPSTREAM_INVALID_RESPONSE";
    private static final String UPSTREAM_ERROR = "UPSTREAM_ERROR";
    private static final String INVALID_RESPONSE_MESSAGE = "Knowledge service returned an invalid active-profile response.";
    private static final String INVALID_ERROR_MESSAGE = "Knowledge service returned an invalid active-profile error response.";
    private static final String UPSTREAM_FAILURE_MESSAGE = "Knowledge active-profile request failed.";

    private final ObjectMapper objectMapper;
    private final CorrelationIdProvider correlationIdProvider;

    void handle(final HttpRequest request, final ClientHttpResponse response) throws IOException {
        final HttpStatusCode statusCode = response.getStatusCode();
        if (statusCode.is3xxRedirection()) {
            throw this.invalidResponse();
        }
        final KnowledgeActiveProfileFailureReason reason = this.reason(statusCode.value());
        if (this.controlledStatus(reason)) {
            throw this.controlledError(response, reason);
        }
        throw this.upstreamFailure();
    }

    private KnowledgeActiveProfileClientException controlledError(final ClientHttpResponse response,
                                                                  final KnowledgeActiveProfileFailureReason reason) {
        try {
            final KnowledgeErrorResponse error = this.objectMapper.readValue(response.getBody(), KnowledgeErrorResponse.class);
            if (error.code() == null || error.code().isBlank() || error.message() == null || error.message().isBlank()) {
                throw this.invalidError();
            }
            return new KnowledgeActiveProfileClientException(
                    reason,
                    error.code(),
                    error.message(),
                    this.correlationId(error.correlationId())
            );
        } catch (final IOException | RuntimeException exception) {
            throw this.invalidError();
        }
    }

    private KnowledgeActiveProfileFailureReason reason(final int status) {
        return switch (status) {
            case 400 -> KnowledgeActiveProfileFailureReason.BAD_REQUEST;
            case 404 -> KnowledgeActiveProfileFailureReason.NOT_FOUND;
            case 409 -> KnowledgeActiveProfileFailureReason.CONFLICT;
            case 422 -> KnowledgeActiveProfileFailureReason.UNPROCESSABLE_ENTITY;
            case 503 -> KnowledgeActiveProfileFailureReason.UNAVAILABLE;
            default -> KnowledgeActiveProfileFailureReason.UPSTREAM_FAILURE;
        };
    }

    private boolean controlledStatus(final KnowledgeActiveProfileFailureReason reason) {
        return reason != KnowledgeActiveProfileFailureReason.UPSTREAM_FAILURE;
    }

    private KnowledgeActiveProfileClientException invalidResponse() {
        return new KnowledgeActiveProfileClientException(
                KnowledgeActiveProfileFailureReason.INVALID_RESPONSE,
                UPSTREAM_INVALID_RESPONSE,
                INVALID_RESPONSE_MESSAGE,
                this.correlationIdProvider.currentOrCreate()
        );
    }

    private KnowledgeActiveProfileClientException invalidError() {
        return new KnowledgeActiveProfileClientException(
                KnowledgeActiveProfileFailureReason.INVALID_RESPONSE,
                UPSTREAM_INVALID_RESPONSE,
                INVALID_ERROR_MESSAGE,
                this.correlationIdProvider.currentOrCreate()
        );
    }

    private KnowledgeActiveProfileClientException upstreamFailure() {
        return new KnowledgeActiveProfileClientException(
                KnowledgeActiveProfileFailureReason.UPSTREAM_FAILURE,
                UPSTREAM_ERROR,
                UPSTREAM_FAILURE_MESSAGE,
                this.correlationIdProvider.currentOrCreate()
        );
    }

    private String correlationId(final String supplied) {
        if (CorrelationIdProvider.isValid(supplied)) {
            return supplied;
        }
        return this.correlationIdProvider.currentOrCreate();
    }
}
