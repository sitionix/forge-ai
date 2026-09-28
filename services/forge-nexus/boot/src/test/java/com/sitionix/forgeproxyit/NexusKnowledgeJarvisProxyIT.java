package com.sitionix.forgeproxyit;

import static com.sitionix.forgeit.wiremock.api.Parameter.equalTo;
import static org.awaitility.Awaitility.await;

import com.sitionix.forgeai.Application;
import com.sitionix.forgeproxyit.infra.NexusInfrastructureMockMvcEndpoints;
import com.sitionix.forgeproxyit.infra.NexusProxyTestManager;
import com.sitionix.forgeproxyit.infra.NexusProxyTestManagerImpl;
import com.sitionix.forgeproxyit.infra.UpstreamInfrastructureWireMockEndpoints;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.wiremock.api.WireMockQueryParams;
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
class NexusKnowledgeJarvisProxyIT extends com.sitionix.forgeproxyit.infra.NexusProxyManagementFixture {

    @Autowired
    private NexusProxyTestManager testManager;

    @Test
    void knowledgeRequestPathQueryAndBodyAreForwarded() {
        final var upstream = this.testManager.wiremock()
                .createMapping(UpstreamInfrastructureWireMockEndpoints.knowledgeQuery())
                .urlWithQueryParam(WireMockQueryParams.create().add("trace", equalTo("knowledge-it")))
                .createDefault();

        this.testManager.mockMvc()
                .ping(NexusInfrastructureMockMvcEndpoints.knowledgeQuery()).header("Host", "127.0.0.1:9099").header("Origin", "http://127.0.0.1:9099").header("X-Forge-CSRF", operatorCsrf).cookie("FG_SESSION", operatorSession)
                .assertDefault();

        await().untilAsserted(upstream::verify);
    }

    @Test
    void jarvisRequestPathAndBodyAreForwarded() {
        final var upstream = this.testManager.wiremock()
                .createMapping(UpstreamInfrastructureWireMockEndpoints.jarvisCommand())
                .plainUrl()
                .createDefault();

        this.testManager.mockMvc()
                .ping(NexusInfrastructureMockMvcEndpoints.jarvisCommand()).header("Host", "127.0.0.1:9099").header("Origin", "http://127.0.0.1:9099").header("X-Forge-CSRF", operatorCsrf).cookie("FG_SESSION", operatorSession)
                .assertDefault();

        await().untilAsserted(upstream::verify);
    }
}
