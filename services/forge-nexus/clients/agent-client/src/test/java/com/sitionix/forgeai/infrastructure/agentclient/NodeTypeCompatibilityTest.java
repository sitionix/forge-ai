package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeType;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RunNodeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeRunResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NodeTypeCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"nodeType\":null}"})
    void legacyNodeRequestDefaultsToAgent(final String json) throws Exception {
        assertThat(this.mapper.readValue(json, NodeRequest.class).nodeType()).isEqualTo(AgentNodeType.AGENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"nodeType\":null}"})
    void legacyNodeResponseDefaultsToAgent(final String json) throws Exception {
        assertThat(this.mapper.readValue(json, NodeResponse.class).nodeType()).isEqualTo(AgentNodeType.AGENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"nodeType\":null}"})
    void legacyRunNodeResponseDefaultsToAgent(final String json) throws Exception {
        assertThat(this.mapper.readValue(json, RunNodeResponse.class).nodeType()).isEqualTo(AgentNodeType.AGENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"nodeType\":null}"})
    void legacyNodeRunResponseDefaultsToAgent(final String json) throws Exception {
        assertThat(this.mapper.readValue(json, NodeRunResponse.class).nodeType()).isEqualTo(AgentNodeType.AGENT);
    }
}
