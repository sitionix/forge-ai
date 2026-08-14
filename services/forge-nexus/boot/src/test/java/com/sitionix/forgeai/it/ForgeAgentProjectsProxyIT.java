package com.sitionix.forgeai.it;

import static com.sitionix.forgeai.it.ForgeAgentProxyFixtures.PROJECT_ID;
import static com.sitionix.forgeit.wiremock.api.Parameter.equalTo;

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
class ForgeAgentProjectsProxyIT extends AbstractForgeAiIT {

    @Autowired
    private ProxyTestManager testManager;

    @Test
    void givenUpstreamProjects_whenListProjects_thenProxyResponseMatches() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.listProjects())
                .plainUrl()
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.listProjects())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenProjectRequest_whenCreateProject_thenRequestAndResponseAreForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.createProject())
                .plainUrl()
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.createProject())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenProjectId_whenDeleteProject_thenDeletePathIsForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.deleteProject())
                .pathPattern(projectWireMockPathParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.deleteProject())
                .withPathParameters(projectMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    private static PathParams projectMockMvcPathParams() {
        return PathParams.create().add("projectId", PROJECT_ID);
    }

    private static WireMockPathParams projectWireMockPathParams() {
        return WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString()));
    }
}
