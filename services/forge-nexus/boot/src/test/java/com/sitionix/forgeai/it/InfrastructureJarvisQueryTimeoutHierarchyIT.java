package com.sitionix.forgeai.it;

import com.sitionix.forgeai.it.infra.InfrastructureProxyAsyncMockMvc;
import com.sitionix.forgeai.it.infra.InfrastructureProxyEndpoint;
import com.sitionix.forgeai.it.infra.ProxyTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.jarvis.read-timeout=120ms",
        "forge.ai.query.human-query.request-timeout=100ms",
        "forge.ai.infrastructure.proxy.knowledge-human-query-transport-grace=50ms",
        "forge.ai.infrastructure.proxy.jarvis-query-transport-grace=150ms",
        "forge.ai.infrastructure.proxy.max-response-body-bytes=6500"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class InfrastructureJarvisQueryTimeoutHierarchyIT extends AbstractForgeAiIT {

    @Autowired
    private ProxyTestManager testManager;

    @Autowired
    private InfrastructureProxyAsyncMockMvc proxyMockMvc;

    @Test
    void itJarvisQueryWaitsPastNormalServiceTimeoutForHumanFlowResponse() {
        this.testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamJarvisQuery())
                .applyDefault(context -> context
                        .plainUrl()
                        .matchesJson("requestProxyJarvisQuery.json")
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyJarvisQuery.json"))
                .delayForResponse(150)
                .create();

        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusJarvisQuery())
                .header("X-Correlation-Id", "corr-jarvis-timeout-hierarchy")
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.answers[0].text", containsString("JarvisGateway")))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").doesNotExist())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.flows").doesNotExist())
                .andExpectPath(MockMvcResultMatchers.content().string(not(containsString("UPSTREAM_TIMEOUT"))))
                .assertDefault();
    }
}
