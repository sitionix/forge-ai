package com.sitionix.forgeai.config;

import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGateway;
import com.sitionix.forgeai.infrastructure.knowledgeclient.HttpKnowledgeGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.docker.compose.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "forge-ai.jobs.scheduling-enabled=false"
        }
)
class KnowledgeClientWiringTest {

    @Autowired
    private KnowledgeGateway knowledgeGateway;

    @Test
    void shouldWireProductionKnowledgeGatewayAdapter() {
        assertThat(this.knowledgeGateway).isInstanceOf(HttpKnowledgeGateway.class);
    }
}
