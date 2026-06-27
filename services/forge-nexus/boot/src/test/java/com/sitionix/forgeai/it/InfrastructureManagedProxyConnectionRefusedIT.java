package com.sitionix.forgeai.it;

import com.sitionix.forgeai.it.infra.InfrastructureProxyAsyncMockMvc;
import com.sitionix.forgeai.it.infra.InfrastructureProxyEndpoint;
import com.sitionix.forgeai.it.infra.ProxyTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge.ai.infrastructure.knowledge.base-url=http://127.0.0.1:1",
        "forge.ai.infrastructure.knowledge.connect-timeout=200ms",
        "forge.ai.infrastructure.knowledge.read-timeout=500ms"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class InfrastructureManagedProxyConnectionRefusedIT extends AbstractForgeAiIT {

    @Autowired
    private InfrastructureProxyAsyncMockMvc proxyMockMvc;

    @Autowired
    @SuppressWarnings("unused")
    private ProxyTestManager testManager;

    @Test
    void itProxy04ConnectionRefusedReturnsStructuredProxyError() {
        this.proxyMockMvc.ping(InfrastructureProxyEndpoint.nexusKnowledgeStatusConnectionRefused())
                .header("X-Correlation-Id", "corr-refused")
                .andExpectPath(MockMvcResultMatchers.header().string("X-Correlation-Id", "corr-refused"))
                .assertDefault();
    }
}
