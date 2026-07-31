package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

final class KnowledgeActiveProfileCorrelation {

    static final String HEADER = "X-Correlation-Id";

    private KnowledgeActiveProfileCorrelation() {
    }

    static String currentOrNew() {
        final ServletRequestAttributes attributes = requestAttributes();
        final HttpServletRequest request = attributes == null ? null : attributes.getRequest();
        final String incoming = request == null ? null : request.getHeader(HEADER);
        if (valid(incoming)) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    static boolean valid(final String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}");
    }

    private static ServletRequestAttributes requestAttributes() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes;
        }
        return null;
    }
}
