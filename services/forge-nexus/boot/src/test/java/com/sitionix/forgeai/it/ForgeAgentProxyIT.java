package com.sitionix.forgeai.it;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeai.it.infra.ForgeAgentProxyEndpoint;
import com.sitionix.forgeai.it.infra.ProxyTestManager;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge.ai.infrastructure.agent.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.agent.connect-timeout=500ms",
        "forge.ai.infrastructure.agent.read-timeout=500ms",
        "forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ForgeAgentProxyIT extends AbstractForgeAiIT {

    @Autowired
    private ProxyTestManager testManager;

    @Test
    void happyCreateListReadUpdateFlow() {
        //given
        final var listProjectsMapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyEndpoint.UPSTREAM_LIST_PROJECTS)
                .createDefault();
        final var createProjectMapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyEndpoint.UPSTREAM_CREATE_PROJECT)
                .createDefault();
        final var listAgentsMapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyEndpoint.UPSTREAM_LIST_PROJECT_AGENTS)
                .createDefault();
        final var createAgentMapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyEndpoint.UPSTREAM_CREATE_AGENT)
                .createDefault();
        final var getAgentMapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyEndpoint.UPSTREAM_GET_AGENT)
                .createDefault();
        final var updateAgentMapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyEndpoint.UPSTREAM_UPDATE_AGENT)
                .createDefault();

        //when then
        this.testManager.mockMvc()
                .ping(ForgeAgentProxyEndpoint.NEXUS_LIST_PROJECTS)
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyEndpoint.NEXUS_CREATE_PROJECT)
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyEndpoint.NEXUS_LIST_PROJECT_AGENTS)
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyEndpoint.NEXUS_CREATE_AGENT)
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyEndpoint.NEXUS_GET_AGENT)
                .assertDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyEndpoint.NEXUS_UPDATE_AGENT)
                .assertDefault();

        listProjectsMapping.verify();
        createProjectMapping.verify();
        listAgentsMapping.verify();
        createAgentMapping.verify();
        getAgentMapping.verify();
        updateAgentMapping.verify();
    }

    @Test
    void upstreamControlledValidationErrorIsPreserved() {
        //given
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyEndpoint.UPSTREAM_CREATE_AGENT_VALIDATION_ERROR)
                .createDefault();

        //when then
        this.testManager.mockMvc()
                .ping(ForgeAgentProxyEndpoint.NEXUS_CREATE_AGENT_VALIDATION_ERROR)
                .assertDefault();

        mapping.verify();
    }

    @Test
    void malformedUpstreamSuccessResponseBecomesBadGateway() {
        //given
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyEndpoint.UPSTREAM_GET_AGENT_MALFORMED_SUCCESS)
                .createDefault();

        //when then
        this.testManager.mockMvc()
                .ping(ForgeAgentProxyEndpoint.NEXUS_GET_AGENT_MALFORMED_SUCCESS)
                .assertDefault();

        mapping.verify();
    }

    @Test
    void upstreamUnavailableBecomesServiceUnavailable() {
        //given
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyEndpoint.UPSTREAM_GET_AGENT)
                .delayForResponse(2000)
                .createDefault();

        //when then
        this.testManager.mockMvc()
                .ping(ForgeAgentProxyEndpoint.NEXUS_GET_AGENT_UPSTREAM_UNAVAILABLE)
                .assertDefault();

        mapping.verify();
    }

    @Test
    void localInvalidRequestDoesNotCallUpstream() {
        //when then
        this.testManager.mockMvc()
                .ping(ForgeAgentProxyEndpoint.NEXUS_CREATE_AGENT_LOCAL_INVALID)
                .assertDefault();

        this.assertNoMatchingUpstreamRequest(ForgeAgentProxyEndpoint.UPSTREAM_CREATE_AGENT);
    }

    private void assertNoMatchingUpstreamRequest(final Endpoint<?, ?> endpoint) {
        assertThatThrownBy(() -> this.testManager.wiremock().check(endpoint).verify())
                .isInstanceOf(AssertionError.class);
    }
}
