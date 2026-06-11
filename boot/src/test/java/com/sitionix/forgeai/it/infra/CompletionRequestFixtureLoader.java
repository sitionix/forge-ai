package com.sitionix.forgeai.it.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class CompletionRequestFixtureLoader {

    private final ObjectMapper objectMapper;

    public CompletionRequestFixtureLoader(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T read(final String resourceName, final Class<T> type) {
        final List<String> candidates = List.of(
                resourceName,
                "forge-it/mockmvc/default/request/" + resourceName,
                "forge-it/mockmvc/request/" + resourceName,
                "forge-it/mockmvc/default/response/" + resourceName,
                "forge-it/mockmvc/response/" + resourceName
        );
        IOException lastException = null;
        for (final String candidate : candidates) {
            try (InputStream inputStream = new ClassPathResource(candidate).getInputStream()) {
                return this.objectMapper.readValue(inputStream, type);
            } catch (final IOException e) {
                lastException = e;
            }
        }
        throw new IllegalStateException("Failed to read test fixture: " + resourceName, lastException);
    }

    public <T> T read(final String resourceName, final Class<T> type, final Consumer<T> mutator) {
        final T value = this.read(resourceName, type);
        mutator.accept(value);
        return value;
    }
}
