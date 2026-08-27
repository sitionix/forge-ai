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
    private static final UUID REPOSITORY_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SERVICE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

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
    void refreshRepositoryFlowsThroughTypedAgentProxy() {
        final var upstream = this.testManager.wiremock()
                .createMapping(ForgeAgentWireMockEndpoints.refreshRepository())
                .pathPattern(WireMockPathParams.create()
                        .add("projectId", equalTo(PROJECT_ID.toString()))
                        .add("repositoryId", equalTo(REPOSITORY_ID.toString())))
                .createDefault();

        this.testManager.mockMvc()
                .ping(NexusAgentMockMvcEndpoints.refreshRepository())
                .withPathParameters(PathParams.create()
                        .add("projectId", PROJECT_ID)
                        .add("repositoryId", REPOSITORY_ID))
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

    @Test
    void logsCreateFlowsThroughTypedAgentProxy() {
        final var upstream = this.testManager.wiremock()
                .createMapping(ForgeAgentWireMockEndpoints.createLogSource())
                .pathPattern(WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString())))
                .createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.createLogSource())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ID)).assertDefault();
        upstream.verify();
    }

    @Test
    void logsListFlowsThroughTypedAgentProxy() {
        final var upstream = this.testManager.wiremock()
                .createMapping(ForgeAgentWireMockEndpoints.listLogSources())
                .pathPattern(WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString())))
                .createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.listLogSources())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ID)).assertDefault();
        upstream.verify();
    }

    @Test
    void logsDiscoveryPreservesCompleteCandidateMetadata() {
        final var upstream = this.testManager.wiremock()
                .createMapping(ForgeAgentWireMockEndpoints.discoverLogs())
                .pathPattern(WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString())))
                .createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.discoverLogs())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ID)).assertDefault();
        upstream.verify();
    }

    @Test
    void runtimeTargetDiscoveryPreservesTypedProjectProxy() {
        final var upstream = this.testManager.wiremock()
                .createMapping(ForgeAgentWireMockEndpoints.discoverRuntimeTargets())
                .pathPattern(WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString())))
                .createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.discoverRuntimeTargets())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ID)).assertDefault();
        upstream.verify();
    }

    @Test
    void logsUpstreamErrorStatusAndBodyArePropagated() {
        final var upstream = this.testManager.wiremock()
                .createMapping(ForgeAgentWireMockEndpoints.createLogSourceConflict())
                .pathPattern(WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString())))
                .createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.createLogSourceConflict())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ID)).assertDefault();
        upstream.verify();
    }

    @Test
    void sshConnectionTestForwardsTypedUnsavedRequestAndReturnsNoContent() {
        final var upstream = this.testManager.wiremock()
                .createMapping(ForgeAgentWireMockEndpoints.testSshConnection())
                .pathPattern(WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString())))
                .createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.testSshConnection())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ID)).assertDefault();
        upstream.verify();
    }

    @Test
    void sshConnectionTestPreservesUpstreamAgentError() {
        final var upstream = this.testManager.wiremock()
                .createMapping(ForgeAgentWireMockEndpoints.testSshConnectionFailure())
                .pathPattern(WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString())))
                .createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.testSshConnectionFailure())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ID)).assertDefault();
        upstream.verify();
    }

    @Test
    void invalidLogsRequestIsRejectedBeforeAnyUpstreamCall() {
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.invalidLogSource())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ID)).assertDefault();
    }

    @Test
    void serviceCreateAndListFlowThroughTypedProxy() {
        var create = this.testManager.wiremock().createMapping(ForgeAgentWireMockEndpoints.createService())
                .pathPattern(WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString())))
                .createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.createService())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ID)).assertDefault();
        create.verify();

        var list = this.testManager.wiremock().createMapping(ForgeAgentWireMockEndpoints.listServices())
                .pathPattern(WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString())))
                .createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.listServices())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ID)).assertDefault();
        list.verify();
    }

    @Test
    void serviceGetUpdateDeleteRuntimeAndLogsFlowThroughTypedProxy() {
        var path = WireMockPathParams.create()
                .add("projectId", equalTo(PROJECT_ID.toString()))
                .add("serviceId", equalTo(SERVICE_ID.toString()));
        var mvcPath = PathParams.create().add("projectId", PROJECT_ID).add("serviceId", SERVICE_ID);

        var get = this.testManager.wiremock().createMapping(ForgeAgentWireMockEndpoints.getService())
                .pathPattern(path).createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.getService())
                .withPathParameters(mvcPath).assertDefault();
        get.verify();

        var update = this.testManager.wiremock().createMapping(ForgeAgentWireMockEndpoints.updateService())
                .pathPattern(path).createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.updateService())
                .withPathParameters(mvcPath).assertDefault();
        update.verify();

        var runtime = this.testManager.wiremock().createMapping(ForgeAgentWireMockEndpoints.serviceRuntime())
                .pathPattern(path).createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.serviceRuntime())
                .withPathParameters(mvcPath).assertDefault();
        runtime.verify();

        var logs = this.testManager.wiremock().createMapping(ForgeAgentWireMockEndpoints.serviceLogs())
                .pathPattern(path).createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.serviceLogs())
                .withPathParameters(mvcPath).assertDefault();
        logs.verify();

        var delete = this.testManager.wiremock().createMapping(ForgeAgentWireMockEndpoints.deleteService())
                .pathPattern(path).createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.deleteService())
                .withPathParameters(mvcPath).assertDefault();
        delete.verify();
    }

    @Test
    void serviceRuntimeUpstreamErrorIsPropagatedWithoutNexusSemantics() {
        var path = WireMockPathParams.create()
                .add("projectId", equalTo(PROJECT_ID.toString()))
                .add("serviceId", equalTo(SERVICE_ID.toString()));
        var upstream = this.testManager.wiremock()
                .createMapping(ForgeAgentWireMockEndpoints.serviceRuntimeFailure())
                .pathPattern(path).createDefault();
        this.testManager.mockMvc().ping(NexusAgentMockMvcEndpoints.serviceRuntimeFailure())
                .withPathParameters(PathParams.create()
                        .add("projectId", PROJECT_ID).add("serviceId", SERVICE_ID))
                .assertDefault();
        upstream.verify();
    }
}
