package com.sitionix.forgeai.api.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InfrastructureProxyRouteRegistryTest {

    @Test
    void explanationRoutesUseSynchronousExplanationReadTimeoutContract() {
        final InfrastructureProxyRouteRegistry registry = new InfrastructureProxyRouteRegistry();

        assertThat(registry.require("knowledge.query.flow-explanations").readTimeout())
                .isEqualTo(InfrastructureProxyRouteRegistry.KNOWLEDGE_EXPLANATION_READ_TIMEOUT);
        assertThat(registry.require("knowledge.query.tool-context").readTimeout())
                .isEqualTo(InfrastructureProxyRouteRegistry.KNOWLEDGE_EXPLANATION_READ_TIMEOUT);
        assertThat(registry.require("knowledge.status").readTimeout()).isNull();
    }
}
