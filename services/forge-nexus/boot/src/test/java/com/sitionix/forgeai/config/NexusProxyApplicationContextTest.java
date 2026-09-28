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

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"includeTaskRepositories\":null}",
            "{\"includeTaskRepositories\":\"false\"}",
            "{\"workspaceRepositoryIds\":null}",
            "{\"workspaceRepositoryIds\":\"bad\"}",
            "{\"workspaceRepositoryIds\":[null]}",
            "{\"workspaceRepositoryIds\":[\"not-a-uuid\"]}"
    })
    void configuredMapperRejectsInvalidWorkingRepositoriesAtBothBoundaries(String json) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> this.objectMapper.readValue(json,
                com.sitionix.forgeai.api.agentproxy.NodeRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> this.objectMapper.readValue(json,
                com.sitionix.forgeai.infrastructure.agentclient.dto.NodeResponse.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }
}
