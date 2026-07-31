package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.domain.port.KnowledgeActiveProfileClient;
import feign.FeignException;
import feign.RetryableException;
import feign.codec.DecodeException;
import java.net.SocketTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeActiveProfileClientAdapter implements KnowledgeActiveProfileClient {

    private final KnowledgeActiveProfileFeignClient feignClient;
    private final KnowledgeActiveProfileClientMapper mapper;
    private final KnowledgeActiveProfileClientProperties properties;

    public KnowledgeActiveProfileClientAdapter(final KnowledgeActiveProfileFeignClient feignClient,
                                               final KnowledgeActiveProfileClientMapper mapper,
                                               final KnowledgeActiveProfileClientProperties properties) {
        this.feignClient = feignClient;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public ActiveProfile getActiveProfile() {
        this.requireEnabled();
        try {
            return this.mapper.toDomain(this.feignClient.getActiveProfile());
        } catch (final RuntimeException exception) {
            throw this.mapFailure(exception);
        }
    }

    @Override
    public ActiveLlmProfileUpdateResult updateActiveLlmProfile(final UpdateActiveLlmProfileCommand command) {
        this.requireEnabled();
        try {
            return this.mapper.toDomain(this.feignClient.updateActiveLlmProfile(this.mapper.toRequest(command)));
        } catch (final RuntimeException exception) {
            throw this.mapFailure(exception);
        }
    }

    private void requireEnabled() {
        if (!this.properties.isEnabled()) {
            throw new KnowledgeActiveProfileClientException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "UPSTREAM_UNAVAILABLE",
                    "Knowledge service is unavailable.",
                    KnowledgeActiveProfileCorrelation.currentOrNew()
            );
        }
    }

    private RuntimeException mapFailure(final RuntimeException exception) {
        if (exception instanceof KnowledgeActiveProfileClientException) {
            return exception;
        }
        if (exception instanceof RetryableException) {
            return new KnowledgeActiveProfileClientException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "UPSTREAM_UNAVAILABLE",
                    "Knowledge service is unavailable.",
                    KnowledgeActiveProfileCorrelation.currentOrNew()
            );
        }
        if (exception instanceof DecodeException) {
            if (this.timeoutLike(exception)) {
                return new KnowledgeActiveProfileClientException(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "UPSTREAM_UNAVAILABLE",
                        "Knowledge service is unavailable.",
                        KnowledgeActiveProfileCorrelation.currentOrNew()
                );
            }
            return new KnowledgeActiveProfileClientException(
                    HttpStatus.BAD_GATEWAY.value(),
                    "UPSTREAM_INVALID_RESPONSE",
                    "Knowledge service returned an invalid active-profile response.",
                    KnowledgeActiveProfileCorrelation.currentOrNew()
            );
        }
        if (exception instanceof FeignException) {
            if (this.timeoutLike(exception)) {
                return new KnowledgeActiveProfileClientException(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "UPSTREAM_UNAVAILABLE",
                        "Knowledge service is unavailable.",
                        KnowledgeActiveProfileCorrelation.currentOrNew()
                );
            }
            return new KnowledgeActiveProfileClientException(
                    HttpStatus.BAD_GATEWAY.value(),
                    "UPSTREAM_ERROR",
                    "Knowledge active-profile request failed.",
                    KnowledgeActiveProfileCorrelation.currentOrNew()
            );
        }
        return exception;
    }

    private boolean timeoutLike(final Throwable throwable) {
        if (this.causedBySocketTimeout(throwable)) {
            return true;
        }
        final String message = throwable == null ? null : throwable.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("timed out");
    }

    private boolean causedBySocketTimeout(final Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof SocketTimeoutException) {
            return true;
        }
        return this.causedBySocketTimeout(throwable.getCause());
    }
}
