package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileFailureReason;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.domain.port.CorrelationIdProvider;
import com.sitionix.forgeai.domain.port.KnowledgeActiveProfileClient;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
public class KnowledgeActiveProfileClientAdapter implements KnowledgeActiveProfileClient {

    private final KnowledgeActiveProfileHttpClient httpClient;
    private final KnowledgeActiveProfileClientMapper mapper;
    private final KnowledgeActiveProfileClientProperties properties;
    private final CorrelationIdProvider correlationIdProvider;

    @Override
    public ActiveProfile getActiveProfile() {
        this.requireEnabled();
        return this.handleClientCall(() -> this.mapper.toDomain(this.httpClient.getActiveProfile()));
    }

    @Override
    public ActiveLlmProfileUpdateResult updateActiveLlmProfile(final UpdateActiveLlmProfileCommand command) {
        this.requireEnabled();
        return this.handleClientCall(() -> this.mapper.toDomain(this.httpClient.updateActiveLlmProfile(this.mapper.toRequest(command))));
    }

    private <T> T handleClientCall(final Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (final KnowledgeActiveProfileClientException exception) {
            throw exception;
        } catch (final IllegalArgumentException | HttpMessageConversionException exception) {
            throw this.invalidResponse();
        } catch (final ResourceAccessException exception) {
            throw this.unavailable();
        } catch (final RestClientResponseException exception) {
            throw this.upstreamFailure();
        } catch (final RestClientException exception) {
            throw this.restClientFailure(exception);
        } catch (final RuntimeException exception) {
            throw this.upstreamFailure();
        }
    }

    private void requireEnabled() {
        if (!this.properties.enabled()) {
            throw this.unavailable();
        }
    }

    private KnowledgeActiveProfileClientException restClientFailure(final RestClientException exception) {
        if (this.causedBy(exception, HttpMessageConversionException.class)) {
            return this.invalidResponse();
        }
        if (this.causedBy(exception, SocketTimeoutException.class, HttpTimeoutException.class, ConnectException.class)) {
            return this.unavailable();
        }
        return this.upstreamFailure();
    }

    private KnowledgeActiveProfileClientException unavailable() {
        return new KnowledgeActiveProfileClientException(
                KnowledgeActiveProfileFailureReason.UNAVAILABLE,
                "UPSTREAM_UNAVAILABLE",
                "Knowledge service is unavailable.",
                this.correlationIdProvider.currentOrCreate()
        );
    }

    private KnowledgeActiveProfileClientException invalidResponse() {
        return new KnowledgeActiveProfileClientException(
                KnowledgeActiveProfileFailureReason.INVALID_RESPONSE,
                "UPSTREAM_INVALID_RESPONSE",
                "Knowledge service returned an invalid active-profile response.",
                this.correlationIdProvider.currentOrCreate()
        );
    }

    private KnowledgeActiveProfileClientException upstreamFailure() {
        return new KnowledgeActiveProfileClientException(
                KnowledgeActiveProfileFailureReason.UPSTREAM_FAILURE,
                "UPSTREAM_ERROR",
                "Knowledge active-profile request failed.",
                this.correlationIdProvider.currentOrCreate()
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
