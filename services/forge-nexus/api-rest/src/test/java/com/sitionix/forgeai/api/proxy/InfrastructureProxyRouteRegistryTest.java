package com.sitionix.forgeai.api.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class InfrastructureProxyRouteRegistryTest {

    @Test
    void explanationRoutesUseSynchronousExplanationReadTimeoutContract() {
        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        final InfrastructureProxyRouteRegistry registry = new InfrastructureProxyRouteRegistry(
                properties,
                new ForgeAiFlowExplanationProperties()
        );

        assertThat(registry.require("knowledge.query.flow-explanations").readTimeout())
                .isEqualTo(Duration.ofSeconds(185));
        assertThat(registry.require("knowledge.query.tool-context").readTimeout())
                .isEqualTo(Duration.ofSeconds(185));
        assertThat(registry.require("knowledge.status").readTimeout()).isNull();
    }

    @Test
    void explanationRouteTimeoutTracksConfiguredKnowledgeDeadlineAndTransportGrace() {
        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        properties.getProxy().setKnowledgeExplanationTransportGrace(Duration.ofSeconds(7));
        final ForgeAiFlowExplanationProperties flowExplanationProperties = new ForgeAiFlowExplanationProperties();
        flowExplanationProperties.setRequestTimeoutSeconds(42);

        final InfrastructureProxyRouteRegistry registry = new InfrastructureProxyRouteRegistry(
                properties,
                flowExplanationProperties
        );

        assertThat(registry.require("knowledge.query.flow-explanations").readTimeout())
                .isEqualTo(Duration.ofSeconds(49));
        assertThat(registry.require("knowledge.query.tool-context").readTimeout())
                .isEqualTo(Duration.ofSeconds(49));
    }
}
