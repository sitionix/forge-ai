package com.sitionix.forgeai.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestInitializer;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class NexusCorrelationRestClientInitializer implements ClientHttpRequestInitializer {

    private final HttpServletRequest request;

    @Override
    public void initialize(final ClientHttpRequest outboundRequest) {
        final String correlationId = this.currentCorrelationId();
        if (NexusCorrelationFilter.valid(correlationId)) {
            outboundRequest.getHeaders().set(NexusCorrelationFilter.CORRELATION_HEADER, correlationId);
        }
    }

    private String currentCorrelationId() {
        if (this.request.getAttribute(NexusCorrelationFilter.CORRELATION_ATTRIBUTE) instanceof String correlationId
                && NexusCorrelationFilter.valid(correlationId)) {
            return correlationId;
        }
        final String header = this.request.getHeader(NexusCorrelationFilter.CORRELATION_HEADER);
        if (NexusCorrelationFilter.valid(header)) {
            return header;
        }
        return null;
    }
}
