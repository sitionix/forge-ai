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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.proxy.InfrastructureProxyTransport;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class ForgeAiInfrastructureJarvisControllerTest {

    private final InfrastructureProxyTransport transport = mock(InfrastructureProxyTransport.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ForgeAiInfrastructureJarvisController controller = new ForgeAiInfrastructureJarvisController(this.transport, this.objectMapper);
    private final HttpHeaders headers = new HttpHeaders();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void statusDelegatesToGenericProxyRoute() {
        this.stub();

        this.controller.status(this.headers, this.request);

        verify(this.transport).forward("jarvis.status", Map.of(), null, this.headers, this.request);
    }

    @Test
    void actionsDelegatesToGenericProxyRoute() {
        this.stub();

        this.controller.actions(this.headers, this.request);

        verify(this.transport).forward("jarvis.actions", Map.of(), null, this.headers, this.request);
    }

    @Test
    void commandDelegatesRawBodyToGenericProxyRoute() {
        this.stub();
        final byte[] body = "{\"text\":\"status\"}".getBytes(StandardCharsets.UTF_8);

        this.controller.command(body, this.headers, this.request);

        verify(this.transport).forward("jarvis.command", Map.of(), body, this.headers, this.request);
    }

    @Test
    void querySerializesMinimalRequestAndDelegatesToGenericProxyRoute() {
        this.stub("{\"answerLanguage\":\"uk\",\"answers\":[{\"source\":\"source-a\",\"entrypoint\":\"JarvisGateway\",\"text\":\"ok\"}],\"diagnostics\":[]}");
        final JarvisKnowledgeQueryRequest body = new JarvisKnowledgeQueryRequest(
                "JarvisGateway",
                null,
                null,
                null,
                null
        );

        this.controller.query(body, this.headers, this.request);

        final byte[] expectedBody = "{\"queryText\":\"JarvisGateway\"}".getBytes(StandardCharsets.UTF_8);
        verify(this.transport).forward(
                eq("jarvis.query"),
                eq(Map.of()),
                argThat(actual -> Arrays.equals(actual, expectedBody)),
                same(this.headers),
                same(this.request)
        );
    }

    @Test
    void querySerializesFullFlowExplanationRequestAndDelegatesToGenericProxyRoute() {
        this.stub("{\"answerLanguage\":\"uk\",\"answers\":[{\"source\":\"source-a\",\"entrypoint\":\"JarvisGateway\",\"text\":\"ok\"}],\"diagnostics\":[]}");
        final JarvisKnowledgeQueryRequest body = new JarvisKnowledgeQueryRequest(
                "JarvisGateway",
                JarvisKnowledgeQueryIntent.FLOW_EXPLANATION,
                "uk",
                false,
                null
        );

        this.controller.query(body, this.headers, this.request);

        final byte[] expectedBody = ("{\"queryText\":\"JarvisGateway\",\"intent\":\"FLOW_EXPLANATION\","
                + "\"answerLanguage\":\"uk\",\"includeTests\":false}").getBytes(StandardCharsets.UTF_8);
        verify(this.transport).forward(
                eq("jarvis.query"),
                eq(Map.of()),
                argThat(actual -> Arrays.equals(actual, expectedBody)),
                same(this.headers),
                same(this.request)
        );
    }

    @Test
    void queryPreservesSuccessfulHumanAnswerBytes() throws Exception {
        final String response = "{\"answerLanguage\":\"uk\",\"answers\":[{\"source\":\"source-a\",\"entrypoint\":\"JarvisGateway\",\"text\":\"ok\"}],\"diagnostics\":[]}";
        this.stub(response);

        final ResponseEntity<byte[]> result = this.controller.query(
                new JarvisKnowledgeQueryRequest("App.tsx", null, null, null, null),
                this.headers,
                this.request
        ).join();
        final Map<?, ?> body = this.objectMapper.readValue(result.getBody(), Map.class);

        assertThat(body.get("answerLanguage")).isEqualTo("uk");
        assertThat(body.containsKey("queryId")).isFalse();
        assertThat(body.containsKey("status")).isFalse();
        assertThat(body.containsKey("matchedNodes")).isFalse();
        assertThat(body.containsKey("flows")).isFalse();
        assertThat((List<?>) body.get("answers")).hasSize(1);
    }

    @Test
    void queryValidationErrorDoesNotProxyBlankQuery() throws Exception {
        final ResponseEntity<byte[]> result = this.controller.query(
                new JarvisKnowledgeQueryRequest("   ", null, null, null, null),
                this.headers,
                this.request
        ).join();

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        final Map<?, ?> body = this.objectMapper.readValue(result.getBody(), Map.class);
        assertThat(body.get("title")).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("details")).asString().contains("queryText");
        verifyNoInteractions(this.transport);
    }

    @Test
    void queryValidationErrorDoesNotProxyOutOfRangeMaxFlows() throws Exception {
        final ResponseEntity<byte[]> result = this.controller.query(
                new JarvisKnowledgeQueryRequest("JarvisGateway", JarvisKnowledgeQueryIntent.FLOW_EXPLANATION, "uk", false, 11),
                this.headers,
                this.request
        ).join();

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        final Map<?, ?> body = this.objectMapper.readValue(result.getBody(), Map.class);
        assertThat(body.get("title")).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("details")).asString().contains("maxFlows");
        verifyNoInteractions(this.transport);
    }

    private void stub() {
        this.stub("{}");
    }

    private void stub(final String body) {
        when(this.transport.forward(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ResponseEntity.ok(body.getBytes(StandardCharsets.UTF_8))));
    }
}
