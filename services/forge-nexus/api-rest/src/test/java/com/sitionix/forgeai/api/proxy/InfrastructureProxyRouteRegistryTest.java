package com.sitionix.forgeai.api.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class InfrastructureProxyRouteRegistryTest {

    @Test
    void explanationRoutesUseSynchronousExplanationReadTimeoutContract() {
        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        final InfrastructureProxyRouteRegistry registry = new InfrastructureProxyRouteRegistry(properties);

        assertThat(registry.require("knowledge.query.flow-explanations").readTimeout())
                .isEqualTo(Duration.ofSeconds(95));
        assertThat(registry.require("knowledge.query.tool-context").readTimeout())
                .isEqualTo(Duration.ofSeconds(95));
        assertThat(registry.require("knowledge.status").readTimeout()).isNull();
    }

    @Test
    void explanationRouteTimeoutTracksConfiguredKnowledgeDeadlineAndTransportGrace() {
        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        properties.getProxy().setKnowledgeExplanationRequestDeadline(Duration.ofSeconds(42));
        properties.getProxy().setKnowledgeExplanationTransportGrace(Duration.ofSeconds(7));

        final InfrastructureProxyRouteRegistry registry = new InfrastructureProxyRouteRegistry(properties);

        assertThat(registry.require("knowledge.query.flow-explanations").readTimeout())
                .isEqualTo(Duration.ofSeconds(49));
        assertThat(registry.require("knowledge.query.tool-context").readTimeout())
                .isEqualTo(Duration.ofSeconds(49));
    }
}
