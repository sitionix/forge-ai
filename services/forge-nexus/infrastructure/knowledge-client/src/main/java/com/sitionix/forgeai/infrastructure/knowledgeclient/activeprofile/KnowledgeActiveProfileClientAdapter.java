package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.domain.port.KnowledgeActiveProfileClient;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileRequest;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

@Component
@RequiredArgsConstructor
public class KnowledgeActiveProfileClientAdapter implements KnowledgeActiveProfileClient {

    private final KnowledgeActiveProfileHttpClient httpClient;
    private final KnowledgeActiveProfileClientMapper mapper;
    private final KnowledgeActiveProfileClientProperties properties;
    private final KnowledgeActiveProfileResponseValidator responseValidator;
    private final KnowledgeActiveProfileClientFailures failures;

    @Override
    public ActiveProfile getActiveProfile() {
        final KnowledgeActiveProfileOperation operation = KnowledgeActiveProfileOperation.GET_ACTIVE_PROFILE;
        this.requireEnabled(operation);
        final KnowledgeActiveProfileResponse response = this.executeGet(operation);
        this.validateGetResponse(operation, response);
        return this.mapActiveProfileResponse(operation, response);
    }

    @Override
    public ActiveLlmProfileUpdateResult updateActiveLlmProfile(final UpdateActiveLlmProfileCommand command) {
        final KnowledgeActiveProfileOperation operation = KnowledgeActiveProfileOperation.UPDATE_ACTIVE_LLM_PROFILE;
        this.requireEnabled(operation);
        final KnowledgeActiveLlmProfileRequest request = this.mapRequest(operation, command);
        final KnowledgeActiveLlmProfileResponse response = this.executePut(operation, request);
        this.validatePutResponse(operation, response);
        return this.mapUpdateResponse(operation, response);
    }

    private KnowledgeActiveProfileResponse executeGet(final KnowledgeActiveProfileOperation operation) {
        try {
            return this.httpClient.getActiveProfile();
        } catch (final KnowledgeActiveProfileClientException exception) {
            throw exception;
        } catch (final ResourceAccessException exception) {
            throw this.failUnavailable(operation, exception);
        } catch (final UnknownContentTypeException exception) {
            throw this.failInvalidResponse(operation, exception.getStatusCode().value(), exception);
        } catch (final HttpMessageConversionException exception) {
            throw this.failInvalidResponse(operation, exception);
        } catch (final RestClientResponseException exception) {
            throw this.failDependency(operation, exception.getStatusCode().value(), exception);
        } catch (final RestClientException exception) {
            throw this.restClientFailure(operation, exception);
        } catch (final RuntimeException exception) {
            throw this.failDependency(operation, exception);
        }
    }

    private KnowledgeActiveLlmProfileResponse executePut(final KnowledgeActiveProfileOperation operation,
                                                         final KnowledgeActiveLlmProfileRequest request) {
        try {
            return this.httpClient.updateActiveLlmProfile(request);
        } catch (final KnowledgeActiveProfileClientException exception) {
            throw exception;
        } catch (final ResourceAccessException exception) {
            throw this.failUnavailable(operation, exception);
        } catch (final UnknownContentTypeException exception) {
            throw this.failInvalidResponse(operation, exception.getStatusCode().value(), exception);
        } catch (final HttpMessageConversionException exception) {
            throw this.messageConversionFailure(operation, exception);
        } catch (final RestClientResponseException exception) {
            throw this.failDependency(operation, exception.getStatusCode().value(), exception);
        } catch (final RestClientException exception) {
            throw this.restClientFailure(operation, exception);
        } catch (final RuntimeException exception) {
            throw this.failDependency(operation, exception);
        }
    }

    private KnowledgeActiveLlmProfileRequest mapRequest(final KnowledgeActiveProfileOperation operation,
                                                        final UpdateActiveLlmProfileCommand command) {
        try {
            return this.mapper.toRequest(command);
        } catch (final RuntimeException exception) {
            throw this.failDependency(operation, exception);
        }
    }

    private void validateGetResponse(final KnowledgeActiveProfileOperation operation, final KnowledgeActiveProfileResponse response) {
        try {
            this.responseValidator.validateGetResponse(response);
        } catch (final RuntimeException exception) {
            throw this.failInvalidResponse(operation, exception);
        }
    }

    private void validatePutResponse(final KnowledgeActiveProfileOperation operation, final KnowledgeActiveLlmProfileResponse response) {
        try {
            this.responseValidator.validatePutResponse(response);
        } catch (final RuntimeException exception) {
            throw this.failInvalidResponse(operation, exception);
        }
    }

    private ActiveProfile mapActiveProfileResponse(final KnowledgeActiveProfileOperation operation,
                                                   final KnowledgeActiveProfileResponse response) {
        try {
            final ActiveProfile activeProfile = this.mapper.toDomain(response);
            if (activeProfile == null) {
                throw new IllegalArgumentException("mapped active profile must not be null");
            }
            return activeProfile;
        } catch (final RuntimeException exception) {
            throw this.failInvalidResponse(operation, exception);
        }
    }

    private ActiveLlmProfileUpdateResult mapUpdateResponse(final KnowledgeActiveProfileOperation operation,
                                                           final KnowledgeActiveLlmProfileResponse response) {
        try {
            final ActiveLlmProfileUpdateResult result = this.mapper.toDomain(response);
            if (result == null) {
                throw new IllegalArgumentException("mapped active profile update result must not be null");
            }
            return result;
        } catch (final RuntimeException exception) {
            throw this.failInvalidResponse(operation, exception);
        }
    }

    private void requireEnabled(final KnowledgeActiveProfileOperation operation) {
        if (!this.properties.enabled()) {
            throw this.failUnavailable(operation, null);
        }
    }

    private KnowledgeActiveProfileClientException restClientFailure(final KnowledgeActiveProfileOperation operation,
                                                                   final RestClientException exception) {
        if (this.causedBy(exception, UnknownContentTypeException.class)) {
            return this.failInvalidResponse(operation, null, exception);
        }
        if (this.causedBy(exception, HttpMessageNotWritableException.class)) {
            return this.failDependency(operation, exception);
        }
        if (this.causedBy(exception, HttpMessageNotReadableException.class)) {
            return this.failInvalidResponse(operation, exception);
        }
        if (this.causedBy(exception, HttpMessageConversionException.class)) {
            return this.failInvalidResponse(operation, exception);
        }
        if (this.causedBy(exception, SocketTimeoutException.class, HttpTimeoutException.class, ConnectException.class)) {
            return this.failUnavailable(operation, exception);
        }
        return this.failDependency(operation, exception);
    }

    private KnowledgeActiveProfileClientException messageConversionFailure(final KnowledgeActiveProfileOperation operation,
                                                                          final HttpMessageConversionException exception) {
        if (this.causedBy(exception, HttpMessageNotWritableException.class)) {
            return this.failDependency(operation, exception);
        }
        return this.failInvalidResponse(operation, exception);
    }

    private KnowledgeActiveProfileClientException failUnavailable(final KnowledgeActiveProfileOperation operation,
                                                                 final Throwable cause) {
        return this.failures.dependencyUnavailable(operation, null, cause);
    }

    private KnowledgeActiveProfileClientException failInvalidResponse(final KnowledgeActiveProfileOperation operation,
                                                                     final Throwable cause) {
        return this.failures.invalidDependencyResponse(operation, null, cause);
    }

    private KnowledgeActiveProfileClientException failInvalidResponse(final KnowledgeActiveProfileOperation operation,
                                                                     final Integer upstreamStatus,
                                                                     final Throwable cause) {
        return this.failures.invalidDependencyResponse(operation, upstreamStatus, cause);
    }

    private KnowledgeActiveProfileClientException failDependency(final KnowledgeActiveProfileOperation operation,
                                                                final Throwable cause) {
        return this.failures.dependencyFailure(operation, null, cause);
    }

    private KnowledgeActiveProfileClientException failDependency(final KnowledgeActiveProfileOperation operation,
                                                                final Integer upstreamStatus,
                                                                final Throwable cause) {
        return this.failures.dependencyFailure(operation, upstreamStatus, cause);
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
