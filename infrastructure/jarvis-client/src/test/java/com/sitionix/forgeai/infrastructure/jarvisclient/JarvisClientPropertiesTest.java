package com.sitionix.forgeai.infrastructure.jarvisclient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class JarvisClientPropertiesTest {

    @Test
    void localhostBaseUrlIsAllowed() {
        final JarvisClientProperties properties = new JarvisClientProperties();
        properties.setBaseUrl(URI.create("http://localhost:7071"));

        assertDoesNotThrow(properties::validateBaseUrl);
    }

    @Test
    void loopbackBaseUrlIsAllowed() {
        final JarvisClientProperties properties = new JarvisClientProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:7071"));

        assertDoesNotThrow(properties::validateBaseUrl);
    }

    @Test
    void nonLocalhostBaseUrlIsRejected() {
        final JarvisClientProperties properties = new JarvisClientProperties();
        properties.setBaseUrl(URI.create("http://192.168.1.20:7071"));

        assertThrows(IllegalStateException.class, properties::validateBaseUrl);
    }
}
