package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.proxy.InfrastructureProxyTransport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ForgeAiInfrastructureJarvisControllerTest {

    private final InfrastructureProxyTransport transport = mock(InfrastructureProxyTransport.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ForgeAiInfrastructureJarvisController controller = new ForgeAiInfrastructureJarvisController(this.transport);
    private final HttpHeaders headers = new HttpHeaders();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void statusDelegatesToJsonProxyRoute() {
        this.stubJson("{}");

        this.controller.status(this.headers, this.request);

        verify(this.transport).forwardJson("jarvis.status", Map.of(), null, JsonNode.class, this.headers, this.request);
    }

    @Test
    void actionsDelegatesToJsonProxyRoute() {
        this.stubJson("{}");

        this.controller.actions(this.headers, this.request);

        verify(this.transport).forwardJson("jarvis.actions", Map.of(), null, JsonNode.class, this.headers, this.request);
    }

    @Test
    void commandDelegatesJsonBodyToJsonProxyRoute() throws Exception {
        this.stubJson("{}");
        final JsonNode body = this.objectMapper.readTree("{\"text\":\"status\"}");

        this.controller.command(body, this.headers, this.request);

        verify(this.transport).forwardJson("jarvis.command", Map.of(), body, JsonNode.class, this.headers, this.request);
    }

    @Test
    void queryNormalizesTypedRequestAndDelegatesToJsonProxyRoute() {
        this.stubQueryResponse(this.queryResponse());
        final JarvisKnowledgeQueryRequest body = new JarvisKnowledgeQueryRequest(
                " JarvisGateway ",
                null,
                " UK ",
                null,
                null
        );

        this.controller.query(body, this.headers, this.request);

        verify(this.transport).forwardJson(
                eq("jarvis.query"),
                eq(Map.of()),
                argThat(actual -> {
                    final JarvisKnowledgeQueryRequest request = (JarvisKnowledgeQueryRequest) actual;
                    return request.queryText().equals("JarvisGateway")
                            && request.intent() == JarvisKnowledgeQueryIntent.UNKNOWN
                            && request.answerLanguage().equals("uk")
                            && request.includeTests().equals(Boolean.FALSE)
                            && request.maxFlows().equals(10);
                }),
                eq(JarvisKnowledgeQueryResponse.class),
                same(this.headers),
                same(this.request)
        );
    }

    @Test
    void queryReturnsTypedFactualBundleObject() {
        this.stubQueryResponse(this.queryResponse(
                List.of(this.objectMapper.createObjectNode().put("sourceId", "source-a").put("nodeId", "n1")),
                List.of(this.objectMapper.createObjectNode().put("flowId", "flow-1").set("nodeIds", this.objectMapper.createArrayNode().add("n1")))
        ));

        final ResponseEntity<?> result = this.controller.query(
                new JarvisKnowledgeQueryRequest("App.tsx", null, null, null, null),
                this.headers,
                this.request
        ).join();
        final JarvisKnowledgeQueryResponse body = (JarvisKnowledgeQueryResponse) result.getBody();

        assertThat(body).isNotNull();
        assertThat(body.queryId()).isEqualTo("q1");
        assertThat(body.matchedNodes()).hasSize(1);
        assertThat(body.matchedNodes().get(0).get("sourceId").asText()).isEqualTo("source-a");
        assertThat(body.flowPaths()).hasSize(1);
        assertThat(body.flowPaths().get(0).get("flowId").asText()).isEqualTo("flow-1");
    }

    @Test
    void queryAcceptsMinimalRequestThroughSpringAndForwardsNormalizedPlan() throws Exception {
        this.stubQueryResponse(this.queryResponse());
        final MockMvc mockMvc = this.mockMvc();

        final MvcResult result = mockMvc.perform(post("/api/v1/infrastructure/jarvis/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queryText\":\"JarvisGateway\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value("q1"));
        verify(this.transport).forwardJson(
                eq("jarvis.query"),
                eq(Map.of()),
                argThat(actual -> {
                    final JarvisKnowledgeQueryRequest request = (JarvisKnowledgeQueryRequest) actual;
                    return request.queryText().equals("JarvisGateway")
                            && request.intent() == JarvisKnowledgeQueryIntent.UNKNOWN
                            && request.answerLanguage().equals("en")
                            && request.includeTests().equals(Boolean.FALSE)
                            && request.maxFlows().equals(10);
                }),
                eq(JarvisKnowledgeQueryResponse.class),
                any(),
                any()
        );
    }

    @Test
    void queryRejectsOldRequestShapeThroughSpring() throws Exception {
        this.mockMvc().perform(post("/api/v1/infrastructure/jarvis/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qu" + "ery\":\"JarvisGateway\",\"intent\":\"AU" + "TO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));

        verifyNoInteractions(this.transport);
    }

    @Test
    void queryRejectsAutoIntentThroughSpring() throws Exception {
        this.mockMvc().perform(post("/api/v1/infrastructure/jarvis/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queryText\":\"JarvisGateway\",\"intent\":\"AU" + "TO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));

        verifyNoInteractions(this.transport);
    }

    @Test
    void queryRejectsBlankQueryThroughSpring() throws Exception {
        this.mockMvc().perform(post("/api/v1/infrastructure/jarvis/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queryText\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));

        verifyNoInteractions(this.transport);
    }

    @Test
    void queryRejectsStringControlsThroughSpring() throws Exception {
        this.mockMvc().perform(post("/api/v1/infrastructure/jarvis/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queryText\":\"JarvisGateway\",\"includeTests\":\"false\",\"maxFlows\":\"10\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));

        verifyNoInteractions(this.transport);
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(this.controller)
                .setControllerAdvice(new ForgeAiExceptionHandler())
                .build();
    }

    private void stubJson(final String body) {
        try {
            when(this.transport.forwardJson(any(), any(), any(), any(), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(ResponseEntity.ok(this.objectMapper.readTree(body))));
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void stubQueryResponse(final JarvisKnowledgeQueryResponse body) {
        when(this.transport.forwardJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ResponseEntity.ok(body)));
    }

    private JarvisKnowledgeQueryResponse queryResponse() {
        return this.queryResponse(List.of(), List.of());
    }

    private JarvisKnowledgeQueryResponse queryResponse(final List<JsonNode> matchedNodes,
                                                       final List<JsonNode> flowPaths) {
        return new JarvisKnowledgeQueryResponse(
                "q1",
                "OK",
                "UNKNOWN",
                List.of(),
                matchedNodes,
                flowPaths,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                this.objectMapper.createObjectNode(),
                List.of()
        );
    }
}
