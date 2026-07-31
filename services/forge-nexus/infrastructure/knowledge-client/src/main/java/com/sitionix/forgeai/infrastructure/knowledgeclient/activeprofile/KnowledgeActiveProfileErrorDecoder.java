package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeErrorResponse;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpStatus;

final class KnowledgeActiveProfileErrorDecoder implements ErrorDecoder {

    private final ObjectMapper objectMapper;

    KnowledgeActiveProfileErrorDecoder(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Exception decode(final String methodKey, final Response response) {
        try {
            final KnowledgeErrorResponse error = this.error(response);
            if (error.code() == null || error.code().isBlank() || error.message() == null || error.message().isBlank()) {
                return this.malformed();
            }
            return new KnowledgeActiveProfileClientException(
                    response.status(),
                    error.code(),
                    error.message(),
                    error.correlationId()
            );
        } catch (final RuntimeException | IOException exception) {
            return this.malformed();
        }
    }

    private KnowledgeErrorResponse error(final Response response) throws IOException {
        if (response == null || response.body() == null) {
            throw new IOException("Missing upstream error body");
        }
        try (InputStream input = response.body().asInputStream()) {
            return this.objectMapper.readValue(input, KnowledgeErrorResponse.class);
        }
    }

    private KnowledgeActiveProfileClientException malformed() {
        return new KnowledgeActiveProfileClientException(
                HttpStatus.BAD_GATEWAY.value(),
                "UPSTREAM_INVALID_RESPONSE",
                "Knowledge service returned an invalid active-profile error response.",
                KnowledgeActiveProfileCorrelation.currentOrNew()
        );
    }
}
