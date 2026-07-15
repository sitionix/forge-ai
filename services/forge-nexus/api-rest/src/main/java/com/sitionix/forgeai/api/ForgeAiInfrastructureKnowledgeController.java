package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.proxy.InfrastructureProxyTransport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ForgeAiInfrastructureKnowledgeController {

    private final InfrastructureProxyTransport proxyTransport;

    @GetMapping("/api/v1/infrastructure/knowledge/status")
    public CompletableFuture<ResponseEntity<byte[]>> status(@RequestHeader final HttpHeaders headers,
                                                            final HttpServletRequest request) {
        return this.proxy("knowledge.status", Map.of(), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/sources")
    public CompletableFuture<ResponseEntity<byte[]>> sources(@RequestHeader final HttpHeaders headers,
                                                             final HttpServletRequest request) {
        return this.proxy("knowledge.sources", Map.of(), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/overview")
    public CompletableFuture<ResponseEntity<byte[]>> overview(@RequestHeader final HttpHeaders headers,
                                                              final HttpServletRequest request) {
        return this.proxy("knowledge.overview", Map.of(), null, headers, request);
    }

    @PostMapping("/api/v1/infrastructure/knowledge/inventory/build")
    public CompletableFuture<ResponseEntity<byte[]>> buildInventory(@RequestBody(required = false) final byte[] body,
                                                                    @RequestHeader final HttpHeaders headers,
                                                                    final HttpServletRequest request) {
        return this.proxy("knowledge.inventory.build", Map.of(), body, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/inventory/status")
    public CompletableFuture<ResponseEntity<byte[]>> inventoryStatus(@RequestHeader final HttpHeaders headers,
                                                                     final HttpServletRequest request) {
        return this.proxy("knowledge.inventory.status", Map.of(), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/inventory/files")
    public CompletableFuture<ResponseEntity<byte[]>> files(@RequestHeader final HttpHeaders headers,
                                                           final HttpServletRequest request) {
        return this.proxy("knowledge.inventory.files", Map.of(), null, headers, request);
    }

    @PostMapping("/api/v1/infrastructure/knowledge/query")
    public CompletableFuture<ResponseEntity<byte[]>> query(@RequestBody(required = false) final byte[] body,
                                                           @RequestHeader final HttpHeaders headers,
                                                           final HttpServletRequest request) {
        return this.proxy("knowledge.query", Map.of(), body, headers, request);
    }

    @PostMapping("/api/v1/infrastructure/knowledge/query/tool-context")
    public CompletableFuture<ResponseEntity<byte[]>> queryToolContext(@RequestBody(required = false) final byte[] body,
                                                                      @RequestHeader final HttpHeaders headers,
                                                                      final HttpServletRequest request) {
        return this.proxy("knowledge.query.tool-context", Map.of(), body, headers, request);
    }

    @PostMapping("/api/v1/infrastructure/knowledge/analysis/build")
    public CompletableFuture<ResponseEntity<byte[]>> buildAnalysis(@RequestBody(required = false) final byte[] body,
                                                                   @RequestHeader final HttpHeaders headers,
                                                                   final HttpServletRequest request) {
        return this.proxy("knowledge.analysis.build", Map.of(), body, headers, request);
    }

    @PostMapping("/api/v1/infrastructure/knowledge/analysis/retry-failed")
    public CompletableFuture<ResponseEntity<byte[]>> retryFailedAnalysis(@RequestBody(required = false) final byte[] body,
                                                                         @RequestHeader final HttpHeaders headers,
                                                                         final HttpServletRequest request) {
        return this.proxy("knowledge.analysis.retry-failed", Map.of(), body, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/jobs/{jobId}")
    public CompletableFuture<ResponseEntity<byte[]>> analysisJob(@PathVariable final String jobId,
                                                                 @RequestHeader final HttpHeaders headers,
                                                                 final HttpServletRequest request) {
        return this.proxy("knowledge.analysis.job", Map.of("jobId", jobId), null, headers, request);
    }

    @PostMapping("/api/v1/infrastructure/knowledge/analysis/jobs/{jobId}/stop")
    public CompletableFuture<ResponseEntity<byte[]>> stopAnalysis(@PathVariable final String jobId,
                                                                  @RequestBody(required = false) final byte[] body,
                                                                  @RequestHeader final HttpHeaders headers,
                                                                  final HttpServletRequest request) {
        return this.proxy("knowledge.analysis.job.stop", Map.of("jobId", jobId), body, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/status")
    public CompletableFuture<ResponseEntity<byte[]>> analysisStatus(@RequestHeader final HttpHeaders headers,
                                                                    final HttpServletRequest request) {
        return this.proxy("knowledge.analysis.status", Map.of(), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/current-file-progress")
    public CompletableFuture<ResponseEntity<byte[]>> analysisCurrentFileProgress(@RequestHeader final HttpHeaders headers,
                                                                                final HttpServletRequest request) {
        return this.proxy("knowledge.analysis.current-file-progress", Map.of(), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/files")
    public CompletableFuture<ResponseEntity<byte[]>> analysisFiles(@RequestHeader final HttpHeaders headers,
                                                                   final HttpServletRequest request) {
        return this.proxy("knowledge.analysis.files", Map.of(), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/diagnostics")
    public CompletableFuture<ResponseEntity<byte[]>> analysisDiagnostics(@RequestHeader final HttpHeaders headers,
                                                                        final HttpServletRequest request) {
        return this.proxy("knowledge.analysis.diagnostics", Map.of(), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/graph/metadata")
    public CompletableFuture<ResponseEntity<byte[]>> analysisGraphMetadata(@RequestHeader final HttpHeaders headers,
                                                                          final HttpServletRequest request) {
        return this.proxy("knowledge.graph.metadata", Map.of(), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/graph/manifest")
    public CompletableFuture<ResponseEntity<byte[]>> analysisGraphManifest(@RequestHeader final HttpHeaders headers,
                                                                          final HttpServletRequest request) {
        return this.proxy("knowledge.graph.manifest", Map.of(), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/graph/view")
    public CompletableFuture<ResponseEntity<byte[]>> analysisGraphView(@RequestHeader final HttpHeaders headers,
                                                                      final HttpServletRequest request) {
        return this.proxy("knowledge.graph.view", Map.of(), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/graph/nodes")
    public CompletableFuture<ResponseEntity<byte[]>> analysisGraphNodes(@RequestHeader final HttpHeaders headers,
                                                                       final HttpServletRequest request) {
        return this.proxy("knowledge.graph.nodes", Map.of(), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/graph/edges")
    public CompletableFuture<ResponseEntity<byte[]>> analysisGraphEdges(@RequestHeader final HttpHeaders headers,
                                                                       final HttpServletRequest request) {
        return this.proxy("knowledge.graph.edges", Map.of(), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/graph/node/{nodeId}")
    public CompletableFuture<ResponseEntity<byte[]>> analysisGraphNode(@PathVariable final String nodeId,
                                                                      @RequestHeader final HttpHeaders headers,
                                                                      final HttpServletRequest request) {
        return this.proxy("knowledge.graph.node", Map.of("nodeId", nodeId), null, headers, request);
    }

    @GetMapping("/api/v1/infrastructure/knowledge/analysis/graph/edge/{edgeId}")
    public CompletableFuture<ResponseEntity<byte[]>> analysisGraphEdge(@PathVariable final String edgeId,
                                                                      @RequestHeader final HttpHeaders headers,
                                                                      final HttpServletRequest request) {
        return this.proxy("knowledge.graph.edge", Map.of("edgeId", edgeId), null, headers, request);
    }

    private CompletableFuture<ResponseEntity<byte[]>> proxy(final String route,
                                                            final Map<String, String> pathVariables,
                                                            final byte[] body,
                                                            final HttpHeaders headers,
                                                            final HttpServletRequest request) {
        return this.proxyTransport.forward(route, pathVariables, body, headers, request);
    }
}
