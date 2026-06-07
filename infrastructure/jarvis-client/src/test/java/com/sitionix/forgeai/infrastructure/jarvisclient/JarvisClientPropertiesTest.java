package com.sitionix.forgeai.infrastructure.jarvisclient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class JarvisClientPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:7071",
            "http://localhost:7071"
    })
    void localBaseUrlIsAllowed(final String baseUrl) {
        final JarvisClientProperties properties = new JarvisClientProperties();
        properties.setBaseUrl(URI.create(baseUrl));

        assertDoesNotThrow(properties::validateBaseUrl);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://192.168.1.10:7071",
            "http://example.com:7071",
            "http://0.0.0.0:7071"
    })
    void nonLocalBaseUrlIsRejected(final String baseUrl) {
        final JarvisClientProperties properties = new JarvisClientProperties();
        properties.setBaseUrl(URI.create(baseUrl));

        assertThrows(IllegalStateException.class, properties::validateBaseUrl);
    }

    @Test
    void nonHttpBaseUrlIsRejected() {
        final JarvisClientProperties properties = new JarvisClientProperties();
        properties.setBaseUrl(URI.create("https://localhost:7071"));

        assertThrows(IllegalStateException.class, properties::validateBaseUrl);
    }
}
