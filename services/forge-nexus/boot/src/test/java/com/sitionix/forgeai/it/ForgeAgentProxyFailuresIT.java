package com.sitionix.forgeai.it;

import com.sitionix.forgeai.it.infra.ProxyTestManager;
import com.sitionix.forgeai.it.infra.ForgeAgentProxyMockMvcEndpoint;
import com.sitionix.forgeai.it.infra.ForgeAgentProxyWireMockEndpoint;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import com.sitionix.forgeit.wiremock.api.WireMockPathParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import static com.sitionix.forgeai.it.ForgeAgentProxyFixtures.AGENT_ID;
import static com.sitionix.forgeai.it.ForgeAgentProxyFixtures.PROJECT_ID;
import static com.sitionix.forgeit.wiremock.api.Parameter.equalTo;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge.ai.infrastructure.agent.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.agent.connect-timeout=5s",
        "forge.ai.infrastructure.agent.read-timeout=5s",
        "forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ForgeAgentProxyFailuresIT extends AbstractForgeAiIT {

    @Autowired
    private ProxyTestManager testManager;

    @Test
    void givenControlledUpstreamValidationError_whenCreateAgent_thenErrorIsPreserved() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.createAgentValidationError())
                .pathPattern(projectWireMockPathParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.createAgentValidationError())
                .withPathParameters(projectMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenMalformedSuccessfulUpstreamResponse_whenGetAgent_thenBadGatewayIsReturned() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.getAgentMalformedSuccess())
                .pathPattern(agentWireMockPathParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.getAgentMalformedSuccess())
                .withPathParameters(agentMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenUnavailableUpstream_whenGetAgent_thenServiceUnavailableIsReturned() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.getAgent())
                .pathPattern(agentWireMockPathParams())
                .delayForResponse(7000)
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.getAgentUpstreamUnavailable())
                .withPathParameters(agentMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenLocalInvalidRequest_whenCreateAgent_thenUpstreamIsNotCalled() {
        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.createAgentLocalInvalid())
                .withPathParameters(projectMockMvcPathParams())
                .assertDefault();
    }

    private static PathParams projectMockMvcPathParams() {
        return PathParams.create().add("projectId", PROJECT_ID);
    }

    private static PathParams agentMockMvcPathParams() {
        return PathParams.create().add("agentId", AGENT_ID);
    }

    private static WireMockPathParams projectWireMockPathParams() {
        return WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString()));
    }

    private static WireMockPathParams agentWireMockPathParams() {
        return WireMockPathParams.create().add("agentId", equalTo(AGENT_ID.toString()));
    }

}
