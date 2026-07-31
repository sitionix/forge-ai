package com.sitionix.forgeai.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class NexusCorrelationFilter extends OncePerRequestFilter {

    static final String CORRELATION_HEADER = "X-Correlation-Id";
    static final String CORRELATION_ATTRIBUTE = NexusCorrelationFilter.class.getName() + ".correlationId";
    private static final Pattern VALID_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        final String correlationId = this.resolve(request.getHeader(CORRELATION_HEADER));
        request.setAttribute(CORRELATION_ATTRIBUTE, correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);
        filterChain.doFilter(request, response);
    }

    static boolean valid(final String value) {
        return value != null && VALID_CORRELATION_ID.matcher(value).matches();
    }

    private String resolve(final String supplied) {
        if (valid(supplied)) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }
}
