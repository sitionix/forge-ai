package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeActiveProfileClientPropertiesTest {

    private KnowledgeActiveProfileClientProperties properties;

    @BeforeEach
    void setUp() {
        this.properties = validProperties();
    }

    @Test
    void validLocalHttpConfigurationPassesStartupValidation() {
        // when // then
        assertThatCode(() -> this.properties.validate()).doesNotThrowAnyException();
    }

    @Test
    void missingBaseUrlFailsStartupValidation() {
        // given
        this.properties.setBaseUrl(null);

        // when // then
        assertThatThrownBy(() -> this.properties.validate()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonHttpBaseUrlFailsStartupValidation() {
        // given
        this.properties.setBaseUrl(URI.create("https://127.0.0.1:7081"));

        // when // then
        assertThatThrownBy(() -> this.properties.validate()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void remoteHostFailsStartupValidation() {
        // given
        this.properties.setBaseUrl(URI.create("http://example.com:7081"));

        // when // then
        assertThatThrownBy(() -> this.properties.validate()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonPositiveConnectTimeoutFailsStartupValidation() {
        // given
        this.properties.setConnectTimeout(Duration.ZERO);

        // when // then
        assertThatThrownBy(() -> this.properties.validate()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonPositiveReadTimeoutFailsStartupValidation() {
        // given
        this.properties.setReadTimeout(Duration.ZERO);

        // when // then
        assertThatThrownBy(() -> this.properties.validate()).isInstanceOf(IllegalStateException.class);
    }

    private static KnowledgeActiveProfileClientProperties validProperties() {
        final KnowledgeActiveProfileClientProperties properties = new KnowledgeActiveProfileClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(URI.create("http://127.0.0.1:7081"));
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(120));
        return properties;
    }
}
