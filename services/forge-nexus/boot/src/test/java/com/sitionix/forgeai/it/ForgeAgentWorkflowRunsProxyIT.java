package com.sitionix.forgeai.it;

import static com.sitionix.forgeai.it.ForgeAgentProxyFixtures.RUN_ID;
import static com.sitionix.forgeai.it.ForgeAgentProxyFixtures.WORKFLOW_ID;
import static com.sitionix.forgeit.wiremock.api.Parameter.equalTo;

import com.sitionix.forgeai.it.infra.ForgeAgentProxyMockMvcEndpoint;
import com.sitionix.forgeai.it.infra.ForgeAgentProxyWireMockEndpoint;
import com.sitionix.forgeai.it.infra.ProxyTestManager;
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
class ForgeAgentWorkflowRunsProxyIT extends AbstractForgeAiIT {

    @Autowired
    private ProxyTestManager testManager;

    @Test
    void givenWorkflowRunRequest_whenCreateRun_thenBodyAndWorkflowPathAreForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.createWorkflowRun())
                .pathPattern(workflowWireMockPathParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.createWorkflowRun())
                .withPathParameters(workflowMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenWorkflowId_whenListRuns_thenPathIsForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.listWorkflowRuns())
                .pathPattern(workflowWireMockPathParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.listWorkflowRuns())
                .withPathParameters(workflowMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenRunId_whenGetRun_thenPathIsForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.getWorkflowRun())
                .pathPattern(runWireMockPathParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.getWorkflowRun())
                .withPathParameters(runMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenUpstreamRunCreationError_whenCreateRun_thenControlledErrorIsForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.createWorkflowRunValidationError())
                .pathPattern(workflowWireMockPathParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.createWorkflowRunValidationError())
                .withPathParameters(workflowMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    private static PathParams workflowMockMvcPathParams() {
        return PathParams.create().add("workflowId", WORKFLOW_ID);
    }

    private static PathParams runMockMvcPathParams() {
        return PathParams.create().add("runId", RUN_ID);
    }

    private static WireMockPathParams workflowWireMockPathParams() {
        return WireMockPathParams.create().add("workflowId", equalTo(WORKFLOW_ID.toString()));
    }

    private static WireMockPathParams runWireMockPathParams() {
        return WireMockPathParams.create().add("runId", equalTo(RUN_ID.toString()));
    }
}
