package com.sitionix.forgeai.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.proxy.InfrastructureProxyTransport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class ForgeAiInfrastructureKnowledgeControllerTest {

    private final InfrastructureProxyTransport transport = mock(InfrastructureProxyTransport.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(this.transport);
    private final HttpHeaders headers = new HttpHeaders();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void statusDelegatesToJsonProxyRoute() {
        this.stub();

        this.controller.status(this.headers, this.request);

        verify(this.transport).forwardJson("knowledge.status", Map.of(), null, JsonNode.class, this.headers, this.request);
    }

    @Test
    void analysisBuildDelegatesJsonBodyToJsonProxyRoute() throws Exception {
        this.stub();
        final JsonNode body = this.objectMapper.readTree("{\"sourceIds\":[\"svc\"]}");

        this.controller.buildAnalysis(body, this.headers, this.request);

        verify(this.transport).forwardJson("knowledge.analysis.build", Map.of(), body, JsonNode.class, this.headers, this.request);
    }

    @Test
    void analysisJobPathVariableDelegatesToJsonProxyRoute() {
        this.stub();

        this.controller.analysisJob("job-1", this.headers, this.request);

        verify(this.transport).forwardJson("knowledge.analysis.job", Map.of("jobId", "job-1"), null, JsonNode.class, this.headers, this.request);
    }

    @Test
    void graphDetailPathVariableDelegatesToJsonProxyRoute() {
        this.stub();

        this.controller.analysisGraphNode("node-1", this.headers, this.request);

        verify(this.transport).forwardJson("knowledge.graph.node", Map.of("nodeId", "node-1"), null, JsonNode.class, this.headers, this.request);
    }

    @Test
    void diagnosticsRouteDelegatesToJsonProxyRoute() {
        this.stub();

        this.controller.analysisDiagnostics(this.headers, this.request);

        verify(this.transport).forwardJson("knowledge.analysis.diagnostics", Map.of(), null, JsonNode.class, this.headers, this.request);
    }

    @Test
    void currentFileProgressDelegatesToJsonProxyRoute() {
        this.stub();

        this.controller.analysisCurrentFileProgress(this.headers, this.request);

        verify(this.transport).forwardJson("knowledge.analysis.current-file-progress", Map.of(), null, JsonNode.class, this.headers, this.request);
    }

    @Test
    void graphMetadataDelegatesToJsonProxyRoute() {
        this.stub();

        this.controller.analysisGraphMetadata(this.headers, this.request);

        verify(this.transport).forwardJson("knowledge.graph.metadata", Map.of(), null, JsonNode.class, this.headers, this.request);
    }

    @Test
    void graphViewDelegatesToJsonProxyRoute() {
        this.stub();

        this.controller.analysisGraphView(this.headers, this.request);

        verify(this.transport).forwardJson("knowledge.graph.view", Map.of(), null, JsonNode.class, this.headers, this.request);
    }

    private void stub() {
        when(this.transport.forwardJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ResponseEntity.ok(this.objectMapper.createObjectNode())));
    }
}
