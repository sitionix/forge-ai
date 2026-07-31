package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.port.CorrelationIdProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.INTERFACES)
@RequiredArgsConstructor
public final class ServletCorrelationIdProvider implements CorrelationIdProvider {

    private final HttpServletRequest request;
    private String correlationId;

    @Override
    public String currentOrCreate() {
        if (this.correlationId == null) {
            this.correlationId = this.resolve();
        }
        return this.correlationId;
    }

    private String resolve() {
        final String incoming = this.request.getHeader(CorrelationIdProvider.HEADER_NAME);
        if (CorrelationIdProvider.isValid(incoming)) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
