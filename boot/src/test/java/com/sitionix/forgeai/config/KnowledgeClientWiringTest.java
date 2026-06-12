package com.sitionix.forgeai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGateway;
import com.sitionix.forgeai.infrastructure.knowledgeclient.HttpKnowledgeGateway;
import com.sitionix.forgeai.infrastructure.knowledgeclient.KnowledgeClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeClientWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(KnowledgeClientProperties.class, KnowledgeClientProperties::new)
            .withUserConfiguration(HttpKnowledgeGateway.class)
            .withPropertyValues(
                    "forge.ai.infrastructure.knowledge.mode=http"
            );

    @Test
    void shouldWireProductionKnowledgeGatewayAdapter() {
        this.contextRunner.run(context -> {
            assertThat(context).hasSingleBean(KnowledgeGateway.class);
            assertThat(context.getBean(KnowledgeGateway.class)).isInstanceOf(HttpKnowledgeGateway.class);
        });
    }
}
