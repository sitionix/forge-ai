package com.sitionix.forgeai.api.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class InfrastructureProxyRouteRegistryTest {

    @Test
    void humanQueryRoutesUseSynchronousHumanQueryReadTimeoutContract() {
        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        final InfrastructureProxyRouteRegistry registry = new InfrastructureProxyRouteRegistry(
                properties,
                new ForgeAiHumanQueryProperties()
        );

        assertThat(registry.require("knowledge.query").readTimeout())
                .isEqualTo(Duration.ofSeconds(180));
        assertThat(registry.require("knowledge.query.tool-context").readTimeout())
                .isEqualTo(Duration.ofSeconds(180));
        assertThat(registry.require("jarvis.query").readTimeout())
                .isEqualTo(Duration.ofSeconds(180));
        assertThat(registry.require("knowledge.ai-runtime").upstreamPath().apply(java.util.Map.of()))
                .isEqualTo("/api/v1/knowledge/ai-runtime");
        assertThat(registry.require("knowledge.status").readTimeout()).isNull();
        assertThat(registry.require("jarvis.status").readTimeout()).isNull();
    }

    @Test
    void humanQueryRouteTimeoutsTrackConfiguredKnowledgeDeadline() {
        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        final ForgeAiHumanQueryProperties humanQueryProperties = new ForgeAiHumanQueryProperties();
        humanQueryProperties.setRequestTimeoutSeconds(42);

        final InfrastructureProxyRouteRegistry registry = new InfrastructureProxyRouteRegistry(
                properties,
                humanQueryProperties
        );

        assertThat(registry.require("knowledge.query").readTimeout())
                .isEqualTo(Duration.ofSeconds(42));
        assertThat(registry.require("knowledge.query.tool-context").readTimeout())
                .isEqualTo(Duration.ofSeconds(42));
        assertThat(registry.require("jarvis.query").readTimeout())
                .isEqualTo(Duration.ofSeconds(42));
    }
}
