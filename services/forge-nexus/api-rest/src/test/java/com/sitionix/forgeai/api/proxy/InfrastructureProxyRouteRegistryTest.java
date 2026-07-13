package com.sitionix.forgeai.api.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(registry.require("jarvis.query").readTimeout())
                .isEqualTo(Duration.ofSeconds(190));
        assertThat(registry.require("knowledge.status").readTimeout()).isNull();
        assertThat(registry.require("jarvis.status").readTimeout()).isNull();
    }

    @Test
    void explanationRouteTimeoutsTrackConfiguredKnowledgeDeadlineAndTransportGraces() {
        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        properties.getProxy().setKnowledgeExplanationTransportGrace(Duration.ofSeconds(7));
        properties.getProxy().setJarvisQueryTransportGrace(Duration.ofSeconds(11));
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
        assertThat(registry.require("jarvis.query").readTimeout())
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void invalidKnowledgeTransportTimeoutHierarchyFailsStartup() {
        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        properties.getProxy().setKnowledgeExplanationTransportGrace(Duration.ZERO);

        assertThatThrownBy(() -> new InfrastructureProxyRouteRegistry(properties, new ForgeAiFlowExplanationProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jarvis Knowledge flow explanation transport timeout must exceed");
    }

    @Test
    void invalidJarvisTransportTimeoutHierarchyFailsStartup() {
        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        properties.getProxy().setJarvisQueryTransportGrace(Duration.ZERO);

        assertThatThrownBy(() -> new InfrastructureProxyRouteRegistry(properties, new ForgeAiFlowExplanationProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nexus Jarvis query transport timeout must exceed");
    }
}
