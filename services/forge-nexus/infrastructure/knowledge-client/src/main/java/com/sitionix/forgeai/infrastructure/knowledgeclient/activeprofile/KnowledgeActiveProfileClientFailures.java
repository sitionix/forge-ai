package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileFailureReason;
import com.sitionix.forgeai.domain.port.CorrelationIdProvider;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
final class KnowledgeActiveProfileClientFailures {

    private static final Logger LOG = System.getLogger(KnowledgeActiveProfileClientFailures.class.getName());
    private static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    private static final String UPSTREAM_INVALID_RESPONSE = "UPSTREAM_INVALID_RESPONSE";
    private static final String UPSTREAM_ERROR = "UPSTREAM_ERROR";
    private static final String UNAVAILABLE_MESSAGE = "Knowledge service is unavailable.";
    private static final String INVALID_RESPONSE_MESSAGE = "Knowledge service returned an invalid active-profile response.";
    private static final String FAILURE_MESSAGE = "Knowledge active-profile request failed.";

    private final CorrelationIdProvider correlationIdProvider;

    KnowledgeActiveProfileClientException dependencyUnavailable(final String operation, final Throwable cause) {
        final String correlationId = this.correlationIdProvider.currentOrCreate();
        this.warn(operation, KnowledgeActiveProfileFailureReason.DEPENDENCY_UNAVAILABLE, correlationId, cause);
        return this.failure(
                KnowledgeActiveProfileFailureReason.DEPENDENCY_UNAVAILABLE,
                UPSTREAM_UNAVAILABLE,
                UNAVAILABLE_MESSAGE,
                correlationId
        );
    }

    KnowledgeActiveProfileClientException invalidDependencyResponse(final String operation, final Throwable cause) {
        final String correlationId = this.correlationIdProvider.currentOrCreate();
        this.warn(operation, KnowledgeActiveProfileFailureReason.INVALID_DEPENDENCY_RESPONSE, correlationId, cause);
        return this.failure(
                KnowledgeActiveProfileFailureReason.INVALID_DEPENDENCY_RESPONSE,
                UPSTREAM_INVALID_RESPONSE,
                INVALID_RESPONSE_MESSAGE,
                correlationId
        );
    }

    KnowledgeActiveProfileClientException dependencyFailure(final String operation, final Throwable cause) {
        final String correlationId = this.correlationIdProvider.currentOrCreate();
        this.error(operation, KnowledgeActiveProfileFailureReason.DEPENDENCY_FAILURE, correlationId, cause);
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

    private void warn(final String operation,
                      final KnowledgeActiveProfileFailureReason reason,
                      final String correlationId,
                      final Throwable cause) {
        LOG.log(
                Level.WARNING,
                "Knowledge active-profile client failure operation={0} exception={1} reason={2} correlationId={3}",
                operation,
                this.exceptionClass(cause),
                reason,
                correlationId
        );
    }

    private void error(final String operation,
                       final KnowledgeActiveProfileFailureReason reason,
                       final String correlationId,
                       final Throwable cause) {
        final String message = "Knowledge active-profile client failure operation=" + operation
                + " exception=" + this.exceptionClass(cause)
                + " reason=" + reason
                + " correlationId=" + correlationId;
        if (cause == null) {
            LOG.log(Level.ERROR, message);
            return;
        }
        LOG.log(Level.ERROR, message, cause);
    }

    private String exceptionClass(final Throwable cause) {
        if (cause == null) {
            return "none";
        }
        return cause.getClass().getName();
    }
}
