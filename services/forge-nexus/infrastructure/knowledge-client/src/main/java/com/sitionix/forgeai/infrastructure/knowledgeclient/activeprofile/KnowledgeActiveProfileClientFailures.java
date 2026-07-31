package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileFailureReason;
import com.sitionix.forgeai.domain.port.CorrelationIdProvider;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
final class KnowledgeActiveProfileClientFailures {

    private static final Logger log = Logger.getLogger(KnowledgeActiveProfileClientFailures.class.getName());
    private static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    private static final String UPSTREAM_INVALID_RESPONSE = "UPSTREAM_INVALID_RESPONSE";
    private static final String UPSTREAM_ERROR = "UPSTREAM_ERROR";
    private static final String UNAVAILABLE_MESSAGE = "Knowledge service is unavailable.";
    private static final String INVALID_RESPONSE_MESSAGE = "Knowledge service returned an invalid active-profile response.";
    private static final String FAILURE_MESSAGE = "Knowledge active-profile request failed.";

    private final CorrelationIdProvider correlationIdProvider;

    KnowledgeActiveProfileClientException dependencyUnavailable(final KnowledgeActiveProfileOperation operation,
                                                                final Integer upstreamStatus,
                                                                final Throwable cause) {
        final String correlationId = this.correlationIdProvider.currentOrCreate();
        this.warn(operation, KnowledgeActiveProfileFailureReason.DEPENDENCY_UNAVAILABLE, correlationId, upstreamStatus, cause);
        return this.failure(
                KnowledgeActiveProfileFailureReason.DEPENDENCY_UNAVAILABLE,
                UPSTREAM_UNAVAILABLE,
                UNAVAILABLE_MESSAGE,
                correlationId
        );
    }

    KnowledgeActiveProfileClientException invalidDependencyResponse(final KnowledgeActiveProfileOperation operation,
                                                                    final Integer upstreamStatus,
                                                                    final Throwable cause) {
        final String correlationId = this.correlationIdProvider.currentOrCreate();
        this.warn(operation, KnowledgeActiveProfileFailureReason.INVALID_DEPENDENCY_RESPONSE, correlationId, upstreamStatus, cause);
        return this.failure(
                KnowledgeActiveProfileFailureReason.INVALID_DEPENDENCY_RESPONSE,
                UPSTREAM_INVALID_RESPONSE,
                INVALID_RESPONSE_MESSAGE,
                correlationId
        );
    }

    KnowledgeActiveProfileClientException dependencyFailure(final KnowledgeActiveProfileOperation operation,
                                                            final Integer upstreamStatus,
                                                            final Throwable cause) {
        final String correlationId = this.correlationIdProvider.currentOrCreate();
        this.error(operation, KnowledgeActiveProfileFailureReason.DEPENDENCY_FAILURE, correlationId, upstreamStatus, cause);
        return this.failure(
                KnowledgeActiveProfileFailureReason.DEPENDENCY_FAILURE,
                UPSTREAM_ERROR,
                FAILURE_MESSAGE,
                correlationId
        );
    }

    KnowledgeActiveProfileClientException controlledKnowledgeFailure(final KnowledgeActiveProfileFailureReason reason,
                                                                     final String code,
                                                                     final String message,
                                                                     final String suppliedCorrelationId) {
        return this.failure(
                reason,
                code,
                message,
                this.correlationIdProvider.preserveOrCurrent(suppliedCorrelationId)
        );
    }

    private KnowledgeActiveProfileClientException failure(final KnowledgeActiveProfileFailureReason reason,
                                                          final String code,
                                                          final String message,
                                                          final String correlationId) {
        return new KnowledgeActiveProfileClientException(reason, code, message, correlationId);
    }

    private void warn(final KnowledgeActiveProfileOperation operation,
                      final KnowledgeActiveProfileFailureReason reason,
                      final String correlationId,
                      final Integer upstreamStatus,
                      final Throwable cause) {
        log.warning(this.message(operation, reason, correlationId, upstreamStatus, cause));
    }

    private void error(final KnowledgeActiveProfileOperation operation,
                       final KnowledgeActiveProfileFailureReason reason,
                       final String correlationId,
                       final Integer upstreamStatus,
                       final Throwable cause) {
        final String message = this.message(operation, reason, correlationId, upstreamStatus, cause);
        if (cause == null) {
            log.severe(message);
            return;
        }
        log.log(Level.SEVERE, message, cause);
    }

    private String message(final KnowledgeActiveProfileOperation operation,
                           final KnowledgeActiveProfileFailureReason reason,
                           final String correlationId,
                           final Integer upstreamStatus,
                           final Throwable cause) {
        return "Knowledge active-profile client failure operation=" + operation.diagnosticName()
                + " exception=" + this.exceptionClass(cause)
                + " reason=" + reason
                + " correlationId=" + correlationId
                + " upstreamStatus=" + this.upstreamStatus(upstreamStatus);
    }

    private String upstreamStatus(final Integer upstreamStatus) {
        if (upstreamStatus == null) {
            return "none";
        }
        return upstreamStatus.toString();
    }

    private String exceptionClass(final Throwable cause) {
        if (cause == null) {
            return "none";
        }
        return cause.getClass().getName();
    }
}
