package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeActiveProfileClientPropertiesTest {

    @Test
    void bindsConfiguredValues() {
        final KnowledgeActiveProfileClientProperties properties = new KnowledgeActiveProfileClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(URI.create("http://127.0.0.1:7081"));
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(120));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.baseUrl()).isEqualTo(URI.create("http://127.0.0.1:7081"));
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(120));
    }
}
