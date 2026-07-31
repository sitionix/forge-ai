package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.exception.KnowledgeClientException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeClientCallExecutor {

    public <T> T execute(final Supplier<T> call) {
        try {
            return call.get();
        } catch (final RestClientResponseException exception) {
            throw new KnowledgeClientException(
                    exception.getStatusCode().value(),
                    exception.getResponseBodyAsString(),
                    this.toMap(exception.getResponseHeaders()),
                    exception
            );
        }
    }

    private Map<String, List<String>> toMap(final HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        final Map<String, List<String>> result = new LinkedHashMap<>();
        headers.forEach((name, values) -> result.put(name, values == null ? List.of() : List.copyOf(values)));
        return result;
    }
}
