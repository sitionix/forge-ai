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
    void querySerializesTypedRequestAndDelegatesToGenericProxyRoute() {
        this.stub("{\"queryId\":\"q1\",\"status\":\"OK\",\"intent\":\"AUTO\",\"matchedSources\":[],\"anchors\":[],\"nodes\":[],\"edges\":[],\"verifiedPaths\":[],\"evidence\":[],\"unresolved\":[],\"external\":[],\"coverage\":{},\"diagnostics\":[]}");
        final JarvisKnowledgeQueryRequest body = new JarvisKnowledgeQueryRequest("JarvisGateway", "AUTO", 5, 2);

        this.controller.query(body, this.headers, this.request);

        final byte[] expectedBody = "{\"query\":\"JarvisGateway\",\"intent\":\"AUTO\",\"maxAnchors\":5,\"depth\":2}".getBytes(StandardCharsets.UTF_8);
        verify(this.transport).forward(
                eq("jarvis.query"),
                eq(Map.of()),
                argThat(actual -> Arrays.equals(actual, expectedBody)),
                same(this.headers),
                same(this.request)
        );
    }

    @Test
    void queryPreservesSuccessfulFactualBundleBytes() throws Exception {
        this.stub("""
                {"queryId":"q1","status":"OK","intent":"AUTO","matchedSources":[{"sourceId":"source-a","displayName":"Source A","score":0.98}],"anchors":[{"sourceId":"source-a","nodeId":"n1","stableKey":"source-a|src/App.tsx|FILE","kind":"FILE","label":"App.tsx","score":0.98,"matchReasons":["NAME_MATCH"]}],"nodes":[{"id":"n1","sourceId":"source-a","kind":"FILE","label":"App.tsx"}],"edges":[{"id":"e1","sourceId":"source-a","fromNodeId":"n1","toNodeId":"n2","kind":"CALLS"}],"verifiedPaths":[],"evidence":[{"sourceId":"source-a","nodeId":"n1","text":"export function App() {}"}],"unresolved":[],"external":[],"coverage":{"searchedSourceCount":2,"matchedSourceCount":1,"anchorCount":1,"nodeCount":1,"edgeCount":1,"evidenceCount":1,"truncated":false},"diagnostics":[{"code":"SLICE_TRUNCATED","severity":"INFO","message":"slice limited"}]}
                """);

        final ResponseEntity<byte[]> result = this.controller.query(new JarvisKnowledgeQueryRequest("App.tsx", "AUTO", 5, 2), this.headers, this.request).join();
        final JarvisKnowledgeQueryResponse body = this.objectMapper.readValue(result.getBody(), JarvisKnowledgeQueryResponse.class);

        assertThat(body.queryId()).isEqualTo("q1");
        assertThat(body.anchors()).hasSize(1);
        assertThat(body.anchors().get(0).get("sourceId").asText()).isEqualTo("source-a");
        assertThat(body.nodes()).hasSize(1);
        assertThat(body.edges()).hasSize(1);
        assertThat(body.evidence()).hasSize(1);
        assertThat(body.diagnostics()).hasSize(1);
        assertThat(body.coverage().get("anchorCount").asInt()).isEqualTo(1);
    }

    @Test
    void queryValidationErrorDoesNotProxyBlankQuery() throws Exception {
        final ResponseEntity<byte[]> result = this.controller.query(new JarvisKnowledgeQueryRequest("   ", "AUTO", 5, 2), this.headers, this.request).join();

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        final Map<?, ?> body = this.objectMapper.readValue(result.getBody(), Map.class);
        assertThat(body.get("title")).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("details")).asString().contains("query");
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
