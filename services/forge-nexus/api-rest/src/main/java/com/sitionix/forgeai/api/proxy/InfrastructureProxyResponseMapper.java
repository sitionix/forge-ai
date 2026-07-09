package com.sitionix.forgeai.api.proxy;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class InfrastructureProxyResponseMapper {

    ResponseEntity<InfrastructureProxyErrorResponse> error(final InfrastructureProxyErrorCode code,
                                                           final String message,
                                                           final String correlationId,
                                                           final Integer upstreamStatus,
                                                           final String route,
                                                           final HttpStatus status,
                                                           final Long proxyDurationMs,
                                                           final String errorSource) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", correlationId);
        if (proxyDurationMs != null) {
            headers.set("X-Proxy-Duration-Ms", Long.toString(proxyDurationMs));
        }
        if (errorSource != null && !errorSource.isBlank()) {
            headers.set("X-Proxy-Error-Source", errorSource);
        }
        final InfrastructureProxyErrorResponse body = new InfrastructureProxyErrorResponse(
                code.name(),
                message,
                correlationId,
                upstreamStatus,
                route
        );
        return new ResponseEntity<>(body, headers, status);
    }
}
