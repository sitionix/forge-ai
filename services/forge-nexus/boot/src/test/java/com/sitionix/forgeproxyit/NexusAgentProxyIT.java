package com.sitionix.forgeproxyit;

import static com.sitionix.forgeit.wiremock.api.Parameter.equalTo;

import com.sitionix.forgeai.Application;
import com.sitionix.forgeproxyit.infra.ForgeAgentWireMockEndpoints;
import com.sitionix.forgeproxyit.infra.NexusAgentMockMvcEndpoints;
import com.sitionix.forgeproxyit.infra.NexusProxyTestManager;
import com.sitionix.forgeproxyit.infra.NexusProxyTestManagerImpl;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import com.sitionix.forgeit.mockmvc.api.QueryParams;
import com.sitionix.forgeit.wiremock.api.WireMockPathParams;
import com.sitionix.forgeit.wiremock.api.WireMockQueryParams;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

@IntegrationTest(properties = {
        "forge.ai.infrastructure.agent.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.agent.connect-timeout=5s",
        "forge.ai.infrastructure.agent.read-timeout=5s",
        "forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}"
})
@ContextConfiguration(classes = Application.class)
@Import(NexusProxyTestManagerImpl.class)
@Execution(ExecutionMode.SAME_THREAD)
class NexusAgentProxyIT {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Autowired
    private NexusProxyTestManager testManager;

    @Test
    void postBodyAndResponseFlowThroughTypedAgentProxy() {
        final var upstream = this.testManager.wiremock()
                .createMapping(ForgeAgentWireMockEndpoints.createProject())
                .plainUrl()
                .createDefault();

        this.testManager.mockMvc()
                .ping(NexusAgentMockMvcEndpoints.createProject())
                .assertDefault();

        upstream.verify();
    }

    @Test
    void pathAndQueryParametersReachForgeAgent() {
        final var upstream = this.testManager.wiremock()
                .createMapping(ForgeAgentWireMockEndpoints.listTasks())
                .pathPattern(WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString())))
                .urlWithQueryParam(WireMockQueryParams.create()
                        .add("page", equalTo("2"))
                        .add("size", equalTo("10")))
                .createDefault();

        this.testManager.mockMvc()
                .ping(NexusAgentMockMvcEndpoints.listTasks())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ID))
                .withQueryParameters(QueryParams.create().add("page", "2").add("size", "10"))
                .assertDefault();

        upstream.verify();
    }

    @Test
    void upstreamAgentErrorStatusAndBodyArePropagated() {
        final var upstream = this.testManager.wiremock()
                .createMapping(ForgeAgentWireMockEndpoints.createProjectConflict())
                .plainUrl()
                .createDefault();

        this.testManager.mockMvc()
                .ping(NexusAgentMockMvcEndpoints.createProjectConflict())
                .assertDefault();

        upstream.verify();
    }
}
