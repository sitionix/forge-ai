package com.sitionix.forgeai.api.agentproxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeType;
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
    void legacyAgentRunNodeResponseDefaultsToAgent(final String json) throws Exception {
        assertThat(this.mapper.readValue(json, AgentRunNodeResponse.class).nodeType()).isEqualTo(AgentNodeType.AGENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"nodeType\":null}"})
    void legacyAgentNodeRunResponseDefaultsToAgent(final String json) throws Exception {
        assertThat(this.mapper.readValue(json, AgentNodeRunResponse.class).nodeType()).isEqualTo(AgentNodeType.AGENT);
    }
}
