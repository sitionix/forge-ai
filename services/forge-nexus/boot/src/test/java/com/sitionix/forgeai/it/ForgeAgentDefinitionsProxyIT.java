package com.sitionix.forgeai.it;

import com.sitionix.forgeai.it.infra.ProxyTestManager;
import com.sitionix.forgeai.it.infra.ForgeAgentProxyMockMvcEndpoint;
import com.sitionix.forgeai.it.infra.ForgeAgentProxyWireMockEndpoint;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import static com.sitionix.forgeai.it.ForgeAgentProxyFixtures.AGENT_ID;
import static com.sitionix.forgeai.it.ForgeAgentProxyFixtures.PROJECT_ID;

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
class ForgeAgentDefinitionsProxyIT extends AbstractForgeAiIT {

    @Autowired
    private ProxyTestManager testManager;

    @Test
    void givenProjectId_whenListAgents_thenPathIsForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.listProjectAgents(PROJECT_ID))
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.listProjectAgents())
                .withPathParameters(projectMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenAgentRequest_whenCreateAgent_thenBodyAndProjectPathAreForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.createAgent(PROJECT_ID))
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.createAgent())
                .withPathParameters(projectMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenAgentId_whenGetAgent_thenPathIsForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.getAgent(AGENT_ID))
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.getAgent())
                .withPathParameters(agentMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenAgentUpdate_whenUpdateAgent_thenBodyAndPathAreForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.updateAgent(AGENT_ID))
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.updateAgent())
                .withPathParameters(agentMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    private static PathParams projectMockMvcPathParams() {
        return PathParams.create().add("projectId", PROJECT_ID);
    }

    private static PathParams agentMockMvcPathParams() {
        return PathParams.create().add("agentId", AGENT_ID);
    }

}
