package com.sitionix.forgeai.it;

import com.sitionix.forgeai.it.infra.KnowledgeActiveProfileEndpoint;
import com.sitionix.forgeai.it.infra.ProxyTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.wiremock.internal.domain.RequestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.knowledge.connect-timeout=2s",
        "forge.ai.infrastructure.knowledge.read-timeout=5s",
        "forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class KnowledgeActiveProfileIT extends AbstractForgeAiIT {

    @Autowired
    private ProxyTestManager testManager;

    @Test
    void getHappyPath() {
        final RequestBuilder<?, ?> mapping = this.testManager.wiremock()
                .createMapping(KnowledgeActiveProfileEndpoint.UPSTREAM_GET_ACTIVE_PROFILE)
                .createDefault();

        this.testManager.mockMvc()
                .ping(KnowledgeActiveProfileEndpoint.NEXUS_GET_ACTIVE_PROFILE)
                .assertDefault();

        mapping.verify();
    }

    @Test
    void putHappyPath() {
        final RequestBuilder<?, ?> mapping = this.testManager.wiremock()
                .createMapping(KnowledgeActiveProfileEndpoint.UPSTREAM_PUT_ACTIVE_LLM_PROFILE)
                .createDefault();

        this.testManager.mockMvc()
                .ping(KnowledgeActiveProfileEndpoint.NEXUS_PUT_ACTIVE_LLM_PROFILE)
                .assertDefault();

        mapping.verify();
    }

    @Test
    void validationFailureDoesNotCallUpstream() {
        this.testManager.mockMvc()
                .ping(KnowledgeActiveProfileEndpoint.NEXUS_PUT_ACTIVE_LLM_PROFILE_VALIDATION_FAILED)
                .assertDefault();
    }

    @Test
    void unknownRequestFieldDoesNotCallUpstream() {
        this.testManager.mockMvc()
                .ping(KnowledgeActiveProfileEndpoint.NEXUS_PUT_ACTIVE_LLM_PROFILE_UNKNOWN_FIELD)
                .assertDefault();
    }

    @Test
    void revisionConflictPreservesUpstreamStatusAndError() {
        final RequestBuilder<?, ?> mapping = this.testManager.wiremock()
                .createMapping(KnowledgeActiveProfileEndpoint.UPSTREAM_PUT_ACTIVE_LLM_PROFILE_REVISION_CONFLICT)
                .createDefault();

        this.testManager.mockMvc()
                .ping(KnowledgeActiveProfileEndpoint.NEXUS_PUT_ACTIVE_LLM_PROFILE_REVISION_CONFLICT)
                .assertDefault();

        mapping.verify();
    }

    @Test
    void upstreamUnavailableReturnsServiceUnavailable() {
        final RequestBuilder<?, ?> mapping = this.testManager.wiremock()
                .createMapping(KnowledgeActiveProfileEndpoint.UPSTREAM_GET_ACTIVE_PROFILE)
                .delayForResponse(10000)
                .createDefault();

        this.testManager.mockMvc()
                .ping(KnowledgeActiveProfileEndpoint.NEXUS_GET_ACTIVE_PROFILE_UPSTREAM_UNAVAILABLE)
                .assertDefault();

        mapping.verify();
    }

    @Test
    void malformedUpstreamErrorReturnsBadGateway() {
        final RequestBuilder<?, ?> mapping = this.testManager.wiremock()
                .createMapping(KnowledgeActiveProfileEndpoint.UPSTREAM_GET_ACTIVE_PROFILE_MALFORMED_ERROR)
                .createDefault();

        this.testManager.mockMvc()
                .ping(KnowledgeActiveProfileEndpoint.NEXUS_GET_ACTIVE_PROFILE_MALFORMED_UPSTREAM_ERROR)
                .assertDefault();

        mapping.verify();
    }
}
