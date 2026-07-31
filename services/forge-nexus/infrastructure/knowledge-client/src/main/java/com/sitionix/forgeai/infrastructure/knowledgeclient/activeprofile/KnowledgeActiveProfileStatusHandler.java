package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileFailureReason;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeErrorResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

@RequiredArgsConstructor
final class KnowledgeActiveProfileStatusHandler {

    private final ObjectMapper objectMapper;
    private final KnowledgeActiveProfileClientFailures failures;

    void handle(final HttpRequest request, final ClientHttpResponse response) throws IOException {
        final String operation = this.operation(request);
        final HttpStatusCode statusCode = response.getStatusCode();
        if (statusCode.is3xxRedirection()) {
            throw this.failures.invalidDependencyResponse(operation, null);
        }
        final KnowledgeActiveProfileFailureReason reason = this.reason(statusCode.value());
        if (this.controlledStatus(reason)) {
            throw this.controlledError(operation, response, reason);
        }
        throw this.failures.dependencyFailure(operation, null);
    }

    private KnowledgeActiveProfileClientException controlledError(final String operation,
                                                                  final ClientHttpResponse response,
                                                                  final KnowledgeActiveProfileFailureReason reason) {
        try {
            final KnowledgeErrorResponse error = this.objectMapper.readValue(response.getBody(), KnowledgeErrorResponse.class);
            if (error.code() == null || error.code().isBlank() || error.message() == null || error.message().isBlank()) {
                throw new IllegalArgumentException("controlled Knowledge error must contain code and message");
            }
            return this.failures.controlledKnowledgeFailure(
                    reason,
                    error.code(),
                    error.message(),
                    error.correlationId()
            );
        } catch (final IOException | RuntimeException exception) {
            throw this.failures.invalidDependencyResponse(operation, exception);
        }
    }

    private KnowledgeActiveProfileFailureReason reason(final int status) {
        return switch (status) {
            case 400 -> KnowledgeActiveProfileFailureReason.REQUEST_REJECTED;
            case 404 -> KnowledgeActiveProfileFailureReason.RESOURCE_NOT_FOUND;
            case 409 -> KnowledgeActiveProfileFailureReason.REVISION_CONFLICT;
            case 422 -> KnowledgeActiveProfileFailureReason.SELECTION_REJECTED;
            case 503 -> KnowledgeActiveProfileFailureReason.DEPENDENCY_UNAVAILABLE;
            default -> KnowledgeActiveProfileFailureReason.DEPENDENCY_FAILURE;
        };
    }

    private boolean controlledStatus(final KnowledgeActiveProfileFailureReason reason) {
        return reason != KnowledgeActiveProfileFailureReason.DEPENDENCY_FAILURE;
    }

    private String operation(final HttpRequest request) {
        final String path = request.getURI().getPath();
        return switch (request.getMethod().name()) {
            case "GET" -> "/api/v1/knowledge/active-profile".equals(path) ? "getActiveProfile" : "activeProfileRequest";
            case "PUT" -> "/api/v1/knowledge/active-profile/llm-profile".equals(path)
                    ? "updateActiveLlmProfile"
                    : "activeProfileRequest";
            default -> "activeProfileRequest";
        };
    }
}
