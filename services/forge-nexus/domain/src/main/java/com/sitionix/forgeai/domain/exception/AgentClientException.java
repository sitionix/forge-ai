package com.sitionix.forgeai.domain.exception;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentClientException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;
    private final Map<String, List<String>> responseHeaders;

    public AgentClientException(final int statusCode,
                                final String responseBody,
                                final Map<String, List<String>> responseHeaders,
                                final Throwable cause) {
        super("Forge Agent service returned HTTP status " + statusCode, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.responseHeaders = copyHeaders(responseHeaders);
    }

    public int statusCode() {
        return this.statusCode;
    }

    public String responseBody() {
        return this.responseBody;
    }

    public Map<String, List<String>> responseHeaders() {
        return this.responseHeaders;
    }

    private static Map<String, List<String>> copyHeaders(final Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        final Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, values == null ? List.of() : List.copyOf(values)));
        return Map.copyOf(copy);
    }
}
