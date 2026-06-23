package com.sitionix.forgeai.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.api.proxy.InfrastructureProxyTransport;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class ForgeAiInfrastructureKnowledgeControllerTest {

    private final InfrastructureProxyTransport transport = mock(InfrastructureProxyTransport.class);
    private final ForgeAiInfrastructureKnowledgeController controller = new ForgeAiInfrastructureKnowledgeController(this.transport);
    private final HttpHeaders headers = new HttpHeaders();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void statusDelegatesToGenericProxyRoute() {
        this.stub();

        this.controller.status(this.headers, this.request);

        verify(this.transport).forward("knowledge.status", Map.of(), null, this.headers, this.request);
    }

    @Test
    void analysisBuildDelegatesRawBodyToGenericProxyRoute() {
        this.stub();
        final byte[] body = "{\"sourceIds\":[\"svc\"]}".getBytes(StandardCharsets.UTF_8);

        this.controller.buildAnalysis(body, this.headers, this.request);

        verify(this.transport).forward("knowledge.analysis.build", Map.of(), body, this.headers, this.request);
    }

    @Test
    void analysisJobPathVariableDelegatesToGenericProxyRoute() {
        this.stub();

        this.controller.analysisJob("job-1", this.headers, this.request);

        verify(this.transport).forward("knowledge.analysis.job", Map.of("jobId", "job-1"), null, this.headers, this.request);
    }

    @Test
    void graphDetailPathVariableDelegatesToGenericProxyRoute() {
        this.stub();

        this.controller.analysisGraphNode("node-1", this.headers, this.request);

        verify(this.transport).forward("knowledge.graph.node", Map.of("nodeId", "node-1"), null, this.headers, this.request);
    }

    @Test
    void diagnosticsRouteDelegatesToGenericProxyRoute() {
        this.stub();

        this.controller.analysisDiagnostics(this.headers, this.request);

        verify(this.transport).forward("knowledge.analysis.diagnostics", Map.of(), null, this.headers, this.request);
    }

    private void stub() {
        when(this.transport.forward(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ResponseEntity.ok(new byte[0])));
    }
}
