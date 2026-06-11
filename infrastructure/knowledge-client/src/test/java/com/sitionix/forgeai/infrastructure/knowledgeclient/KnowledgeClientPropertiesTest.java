package com.sitionix.forgeai.infrastructure.knowledgeclient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class KnowledgeClientPropertiesTest {

    @Test
    void localhostBaseUrlAccepted() {
        final KnowledgeClientProperties properties = new KnowledgeClientProperties();
        properties.setBaseUrl(URI.create("http://localhost:7081"));

        properties.validateBaseUrl();
    }

    @Test
    void nonLocalhostBaseUrlRejected() {
        final KnowledgeClientProperties properties = new KnowledgeClientProperties();
        properties.setBaseUrl(URI.create("http://example.com:7081"));

        assertThatThrownBy(properties::validateBaseUrl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localhost");
    }
}
