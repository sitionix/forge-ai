package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.exception.KnowledgeClientException;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeErrorResponse;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

@Slf4j
@RequiredArgsConstructor
public final class KnowledgeClientCallExecutor {

    private static final Set<Integer> SUPPORTED_UPSTREAM_STATUSES = Set.of(400, 404, 409, 422, 503);
    private static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    private static final String UPSTREAM_INVALID_RESPONSE = "UPSTREAM_INVALID_RESPONSE";
    private static final String UPSTREAM_ERROR = "UPSTREAM_ERROR";
    private static final String UNAVAILABLE_MESSAGE = "Knowledge service is unavailable.";
    private static final String INVALID_RESPONSE_MESSAGE = "Knowledge service returned an invalid response.";
    private static final String FAILURE_MESSAGE = "Knowledge request failed.";

    private final KnowledgeActiveProfileJson json;

    public <T> T execute(final Supplier<T> call) {
        try {
            final T response = call.get();
            if (response == null) {
                throw this.badResponse(null);
            }
            return response;
        } catch (final KnowledgeClientException exception) {
            throw exception;
        } catch (final KnowledgeClientHttpStatusException exception) {
            throw this.httpError(exception);
        } catch (final ResourceAccessException exception) {
            throw this.unavailable(exception);
        } catch (final UnknownContentTypeException exception) {
            throw this.badResponse(exception);
        } catch (final HttpMessageNotReadableException exception) {
            throw this.badResponse(exception);
        } catch (final HttpMessageNotWritableException exception) {
            log.error("Knowledge client request serialization failed", exception);
            throw this.dependencyFailure(exception);
        } catch (final HttpMessageConversionException exception) {
            throw this.badResponse(exception);
        } catch (final RestClientResponseException exception) {
            throw this.httpError(exception);
        } catch (final RestClientException exception) {
            throw this.restClientFailure(exception);
        } catch (final RuntimeException exception) {
            log.error("Unexpected Knowledge client failure", exception);
            throw this.dependencyFailure(exception);
        }
    }

    private KnowledgeClientException httpError(final KnowledgeClientHttpStatusException exception) {
        if (exception.statusCode() < 400) {
            log.error("Knowledge client received unexpected upstream success status. upstreamStatus={}", exception.statusCode(), exception);
            return this.badResponse(exception);
        }
        if (!SUPPORTED_UPSTREAM_STATUSES.contains(exception.statusCode())) {
            log.error("Knowledge client received unexpected upstream failure status. upstreamStatus={}", exception.statusCode(), exception);
            return this.dependencyFailure(exception);
        }
        final KnowledgeErrorResponse error = this.parseError(exception.responseBody(), exception);
        return new KnowledgeClientException(
                exception.statusCode(),
                error.code(),
                error.message(),
                error.correlationId(),
                exception
        );
    }

    private KnowledgeClientException httpError(final RestClientResponseException exception) {
        final int statusCode = exception.getStatusCode().value();
        if (statusCode < 400) {
            log.error(
                    "Knowledge client received unexpected upstream success status. upstreamStatus={}",
                    statusCode,
                    exception
            );
            return this.badResponse(exception);
        }
        if (!SUPPORTED_UPSTREAM_STATUSES.contains(statusCode)) {
            log.error(
                    "Knowledge client received unexpected upstream failure status. upstreamStatus={}",
                    statusCode,
                    exception
            );
            return this.dependencyFailure(exception);
        }
        final KnowledgeErrorResponse error = this.parseError(exception.getResponseBodyAsString(), exception);
        return new KnowledgeClientException(
                statusCode,
                error.code(),
                error.message(),
                error.correlationId(),
                exception
        );
    }

    private KnowledgeErrorResponse parseError(final String responseBody, final RuntimeException cause) {
        try {
            final KnowledgeErrorResponse error = this.json.objectMapper().readValue(responseBody, KnowledgeErrorResponse.class);
            if (error.code() == null || error.code().isBlank() || error.message() == null || error.message().isBlank()) {
                throw new IllegalArgumentException("Knowledge error response must contain code and message");
            }
            return error;
        } catch (final RuntimeException | IOException exception) {
            throw this.badResponse(cause);
        }
    }

    private KnowledgeClientException restClientFailure(final RestClientException exception) {
        if (this.causedBy(exception, SocketTimeoutException.class, HttpTimeoutException.class, ConnectException.class)) {
            return this.unavailable(exception);
        }
        if (this.causedBy(exception, UnknownContentTypeException.class, HttpMessageNotReadableException.class)) {
            return this.badResponse(exception);
        }
        if (this.causedBy(exception, HttpMessageNotWritableException.class)) {
            log.error("Knowledge client request serialization failed", exception);
            return this.dependencyFailure(exception);
        }
        if (this.causedBy(exception, HttpMessageConversionException.class)) {
            return this.badResponse(exception);
        }
        log.error("Unexpected Knowledge client failure", exception);
        return this.dependencyFailure(exception);
    }

    private KnowledgeClientException unavailable(final RuntimeException cause) {
        return new KnowledgeClientException(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                UPSTREAM_UNAVAILABLE,
                UNAVAILABLE_MESSAGE,
                null,
                cause
        );
    }

    KnowledgeClientException badResponse(final RuntimeException cause) {
        return new KnowledgeClientException(
                HttpStatus.BAD_GATEWAY.value(),
                UPSTREAM_INVALID_RESPONSE,
                INVALID_RESPONSE_MESSAGE,
                null,
                cause
        );
    }

    KnowledgeClientException dependencyFailure(final RuntimeException cause) {
        return new KnowledgeClientException(
                HttpStatus.BAD_GATEWAY.value(),
                UPSTREAM_ERROR,
                FAILURE_MESSAGE,
                null,
                cause
        );
    }

    @SafeVarargs
    private boolean causedBy(final Throwable throwable, final Class<? extends Throwable>... types) {
        if (throwable == null) {
            return false;
        }
        for (final Class<? extends Throwable> type : types) {
            if (type.isInstance(throwable)) {
                return true;
            }
        }
        return this.causedBy(throwable.getCause(), types);
    }
}
