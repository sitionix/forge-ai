package com.sitionix.forgeai.it;

import com.github.tomakehurst.wiremock.http.Fault;
import com.sitionix.forgeai.it.infra.ProxyTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getAllServeEvents;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.knowledge.connect-timeout=250ms",
        "forge.ai.infrastructure.knowledge.read-timeout=1000ms",
        "forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class KnowledgeActiveProfileIT extends AbstractForgeAiIT {

    private static final String GET_PATH = "/api/v1/knowledge/active-profile";
    private static final String PUT_PATH = "/api/v1/knowledge/active-profile/llm-profile";
    private static final String NEXUS_GET_PATH = "/api/v1/infrastructure/knowledge/active-profile";
    private static final String NEXUS_PUT_PATH = "/api/v1/infrastructure/knowledge/active-profile/llm-profile";

    @Autowired
    private ProxyTestManager testManager;

    @Autowired
    private MockMvc mockMvc;

    @Value("${forge-it.wiremock.base-url}")
    private String wiremockBaseUrl;

    @BeforeEach
    void setUpWireMock() {
        final URI uri = URI.create(this.wiremockBaseUrl);
        configureFor(uri.getHost(), uri.getPort());
        this.testManager.wiremock().reset();
    }

    @Test
    void getActiveProfileWithUsageNull() throws Exception {
        // given
        this.stubGetOk("response/usage-null.json");

        // when // then
        this.mockMvc.perform(MockMvcRequestBuilders.get(NEXUS_GET_PATH).header("X-Correlation-Id", "corr-get-null"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "corr-get-null"))
                .andExpect(content().json(fixture("response/usage-null.json"), true));
    }

    @Test
    void getActiveProfileWithTwoUsageWindows() throws Exception {
        // given
        this.stubGetOk("response/two-usage-windows.json");

        // when // then
        this.mockMvc.perform(MockMvcRequestBuilders.get(NEXUS_GET_PATH).header("X-Correlation-Id", "corr-get-windows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.windows.length()").value(2))
                .andExpect(jsonPath("$.usage.windows[0].kind").value("PRIMARY"))
                .andExpect(jsonPath("$.usage.windows[1].kind").value("SECONDARY"))
                .andExpect(content().json(fixture("response/two-usage-windows.json"), true));
    }

    @Test
    void putValidActiveProfile() throws Exception {
        // given
        stubFor(put(urlEqualTo(PUT_PATH))
                .withRequestBody(equalToJson(fixture("request/put-valid.json"), true, true))
                .willReturn(jsonResponse(200, "response/put-success.json")));

        // when // then
        this.mockMvc.perform(MockMvcRequestBuilders.put(NEXUS_PUT_PATH)
                        .header("X-Correlation-Id", "corr-put")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture("request/put-valid.json")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "corr-put"))
                .andExpect(content().json(fixture("response/put-success.json"), true));
        verify(putRequestedFor(urlEqualTo(PUT_PATH))
                .withHeader(HttpHeaders.CONTENT_TYPE, containing(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(equalToJson(fixture("request/put-valid.json"), true, true)));
    }

    @Test
    void putRequestValidationFailureDoesNotCallUpstream() throws Exception {
        // when // then
        this.mockMvc.perform(MockMvcRequestBuilders.put(NEXUS_PUT_PATH)
                        .header("X-Correlation-Id", "corr-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture("request/put-invalid-validation.json")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Correlation-Id", "corr-validation"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Active LLM profile request is invalid."));
        verify(0, putRequestedFor(urlEqualTo(PUT_PATH)));
    }

    @Test
    void putUnknownJsonPropertyDoesNotCallUpstream() throws Exception {
        // when // then
        this.mockMvc.perform(MockMvcRequestBuilders.put(NEXUS_PUT_PATH)
                        .header("X-Correlation-Id", "corr-unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture("request/put-unknown-field.json")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Correlation-Id", "corr-unknown"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(0, putRequestedFor(urlEqualTo(PUT_PATH)));
    }

    @Test
    void putRevisionConflictPreservesControlledError() throws Exception {
        // given
        stubFor(put(urlEqualTo(PUT_PATH))
                .withRequestBody(equalToJson(fixture("request/put-stale.json"), true, true))
                .willReturn(jsonResponse(409, "response/revision-conflict.json")));

        // when // then
        this.mockMvc.perform(MockMvcRequestBuilders.put(NEXUS_PUT_PATH)
                        .header("X-Correlation-Id", "corr-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture("request/put-stale.json")))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Correlation-Id", "corr-409"))
                .andExpect(jsonPath("$.code").value("ACTIVE_PROFILE_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.message").value("The active profile was changed by another request"))
                .andExpect(jsonPath("$.correlationId").value("corr-409"));
    }

    @Test
    void upstreamProviderUnavailablePreservesControlledError() throws Exception {
        // given
        this.stubGet(503, "response/provider-unavailable.json");

        // when // then
        this.mockMvc.perform(MockMvcRequestBuilders.get(NEXUS_GET_PATH).header("X-Correlation-Id", "corr-request"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Correlation-Id", "corr-503"))
                .andExpect(jsonPath("$.code").value("ACTIVE_LLM_PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The selected provider is unavailable"))
                .andExpect(jsonPath("$.correlationId").value("corr-503"));
    }

    @Test
    void malformedSuccessJsonReturnsSafeBadGateway() throws Exception {
        // given
        this.stubGet(200, "response/malformed-success.json");

        // when // then
        this.mockMvc.perform(MockMvcRequestBuilders.get(NEXUS_GET_PATH).header("X-Correlation-Id", "corr-malformed-success"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string("X-Correlation-Id", "corr-malformed-success"))
                .andExpect(jsonPath("$.code").value("UPSTREAM_INVALID_RESPONSE"))
                .andExpect(content().string(not(containsString(this.wiremockBaseUrl))));
    }

    @Test
    void validJsonWithMissingRequiredFieldsReturnsSafeBadGateway() throws Exception {
        // given
        this.stubGet(200, "response/missing-required-fields.json");

        // when // then
        this.mockMvc.perform(MockMvcRequestBuilders.get(NEXUS_GET_PATH).header("X-Correlation-Id", "corr-invalid-success"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string("X-Correlation-Id", "corr-invalid-success"))
                .andExpect(jsonPath("$.code").value("UPSTREAM_INVALID_RESPONSE"));
    }

    @Test
    void malformedControlledErrorReturnsSafeBadGateway() throws Exception {
        // given
        this.stubGet(409, "response/malformed-error.json");

        // when // then
        this.mockMvc.perform(MockMvcRequestBuilders.get(NEXUS_GET_PATH).header("X-Correlation-Id", "corr-malformed-error"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string("X-Correlation-Id", "corr-malformed-error"))
                .andExpect(jsonPath("$.code").value("UPSTREAM_INVALID_RESPONSE"));
    }

    @Test
    void connectionFailureReturnsSafeServiceUnavailable() throws Exception {
        // given
        stubFor(get(urlEqualTo(GET_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // when // then
        this.mockMvc.perform(MockMvcRequestBuilders.get(NEXUS_GET_PATH).header("X-Correlation-Id", "corr-connection"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Correlation-Id", "corr-connection"))
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    void stableGeneratedCorrelationPropagatesToKnowledgeAndResponse() throws Exception {
        // given
        this.stubGetOk("response/usage-null.json");

        // when
        final MvcResult result = this.mockMvc.perform(MockMvcRequestBuilders.get(NEXUS_GET_PATH))
                .andExpect(status().isOk())
                .andReturn();

        // then
        final String responseCorrelation = result.getResponse().getHeader("X-Correlation-Id");
        final String upstreamCorrelation = getAllServeEvents().getFirst().getRequest().getHeader("X-Correlation-Id");
        assertThat(responseCorrelation).isNotBlank();
        assertThat(upstreamCorrelation).isEqualTo(responseCorrelation);
    }

    @Test
    void redirectResponseIsNotFollowed() throws Exception {
        // given
        stubFor(get(urlEqualTo(GET_PATH))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader(HttpHeaders.LOCATION, "http://127.0.0.1:1/redirected")));

        // when // then
        this.mockMvc.perform(MockMvcRequestBuilders.get(NEXUS_GET_PATH).header("X-Correlation-Id", "corr-redirect"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string("X-Correlation-Id", "corr-redirect"))
                .andExpect(jsonPath("$.code").value("UPSTREAM_INVALID_RESPONSE"));
        verify(1, getRequestedFor(urlEqualTo(GET_PATH)));
        verify(0, postRequestedFor(urlEqualTo("/redirected")));
    }

    private void stubGetOk(final String fixture) throws IOException {
        this.stubGet(200, fixture);
    }

    private void stubGet(final int status, final String fixture) throws IOException {
        stubFor(get(urlEqualTo(GET_PATH)).willReturn(jsonResponse(status, fixture)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(
            final int status,
            final String fixture
    ) throws IOException {
        return aResponse()
                .withStatus(status)
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withBody(fixture(fixture));
    }

    private static String fixture(final String name) throws IOException {
        return new ClassPathResource("forge-it/active-profile/" + name)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
