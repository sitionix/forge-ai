package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.port.CorrelationIdProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.INTERFACES)
@RequiredArgsConstructor
public final class ServletCorrelationIdProvider implements CorrelationIdProvider {

    private static final Pattern VALID_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final HttpServletRequest request;
    private String correlationId;

    @Override
    public String currentOrCreate() {
        if (this.correlationId == null) {
            this.correlationId = this.resolve();
        }
        return this.correlationId;
    }

    @Override
    public String preserveOrCurrent(final String supplied) {
        if (this.valid(supplied)) {
            return supplied;
        }
        return this.currentOrCreate();
    }

    private String resolve() {
        final String incoming = this.request.getHeader(ActiveProfileHttpHeaders.CORRELATION_ID);
        if (this.valid(incoming)) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    private boolean valid(final String value) {
        return value != null && VALID_CORRELATION_ID.matcher(value).matches();
    }
}
