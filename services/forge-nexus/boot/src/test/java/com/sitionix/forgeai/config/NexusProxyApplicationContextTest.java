package com.sitionix.forgeai.config;

import com.sitionix.forgeai.Application;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = Application.class,
        properties = {
                "spring.config.import=",
                "spring.docker.compose.enabled=false",
                "forge.ai.infrastructure.agent.base-url=http://127.0.0.1:7091",
                "forge.ai.infrastructure.knowledge.base-url=http://127.0.0.1:7081",
                "forge.ai.infrastructure.jarvis.base-url=http://127.0.0.1:7071"
        }
)
class NexusProxyApplicationContextTest {

    @Autowired
    private ForgeAgentClient forgeAgentClient;

    @Test
    void startsWithoutLegacyRuntimeConfigurationAndProvidesTypedAgentClient() {
        assertThat(this.forgeAgentClient).isNotNull();
    }
}
