package com.sitionix.forgeai.infrastructure.knowledgeclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisRelationsRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisSymbolsRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayErrorCode;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayException;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildRequest;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class HttpKnowledgeGatewayTest {

    @Test
    void statusProxyMapsSuccess() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(200, """
                {"status":"UP","module":"knowledge","catalog":{"configured":true,"type":"service_catalog"},"inventory":{"implemented":true,"status":"READY","sourceCount":1,"fileCount":2,"skippedCount":7,"skippedBreakdown":{"total":7,"byReason":{"EXCLUDED_BY_PATTERN":5,"BINARY":2}}},"inventoryRefresh":{"enabled":true,"intervalSeconds":300,"status":"READY","runCount":1,"skipCount":0}}
                """));

        final var status = gateway.status();

        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.catalog().configured()).isTrue();
        assertThat(status.inventory().skippedBreakdown().total()).isEqualTo(7);
        assertThat(status.inventory().skippedBreakdown().byReason()).containsEntry("EXCLUDED_BY_PATTERN", 5);
        assertThat(status.inventoryRefresh().intervalSeconds()).isEqualTo(300);
    }

    @Test
    void sourcesProxyMapsSuccess() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(200, """
                {"catalog":{"configured":true,"type":"service_catalog"},"sources":[{"sourceId":"svc","displayName":"Service","group":"backend","path":"svc","rootExists":true,"tags":["java"],"domainKeywords":[],"ownsBusinessAreas":[],"tests":[]}],"diagnostics":[]}
                """));

        final var sources = gateway.sources();

        assertThat(sources.sources()).hasSize(1);
        assertThat(sources.sources().getFirst().sourceId()).isEqualTo("svc");
    }

    @Test
    void servicesStatusProxyMapsSuccess() {
        final FakeHttpClient client = new FakeHttpClient(200, """
                {"services":[{"sourceId":"svc","label":"Service","group":"backend","path":"svc","rootExists":true,"tags":["java"],"inventory":{"status":"READY","eligibleFileCount":2,"skippedCount":1},"analysis":{"status":"PARTIAL","inventoryFileCount":2,"analyzedFileCount":1,"percent":50.0,"failedFileCount":0},"facts":{"symbolCount":3,"relationCount":4},"diagnostics":[],"details":{"symbols":{"symbols":[{"symbolId":"n1","sourceId":"svc","relativePath":"A.java","name":"run","kind":"CALLABLE","roles":[]}],"total":1,"limit":20,"offset":0},"relations":{"relations":[],"total":0,"limit":20,"offset":0},"failures":{"files":[],"total":0,"limit":10,"offset":0}}}],"activeJob":null}
                """);
        final HttpKnowledgeGateway gateway = gateway(client);

        final var result = gateway.servicesStatus();

        assertThat(result.services()).hasSize(1);
        assertThat(result.services().getFirst().analysis().analyzedFileCount()).isEqualTo(1);
        assertThat(result.services().getFirst().facts().relationCount()).isEqualTo(4);
        assertThat(result.services().getFirst().details().symbols().total()).isEqualTo(1);
        assertThat(client.lastRequest.uri().getPath()).isEqualTo("/api/v1/knowledge/services/status");
    }

    @Test
    void servicesStatusProxyPassesDetailsSourceId() {
        final FakeHttpClient client = new FakeHttpClient(200, """
                {"services":[],"activeJob":null}
                """);
        final HttpKnowledgeGateway gateway = gateway(client);

        gateway.servicesStatus("svc");

        assertThat(client.lastRequest.uri().getPath()).isEqualTo("/api/v1/knowledge/services/status");
        assertThat(client.lastRequest.uri().getQuery()).isEqualTo("detailsSourceId=svc");
    }

    @Test
    void inventoryBuildProxyMapsSuccess() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(200, """
                {"status":"COMPLETED","sourceCount":1,"fileCount":3,"skippedCount":2,"skippedBreakdown":{"total":2,"byReason":{"NOT_INCLUDED":1,"TOO_LARGE":1}},"startedAt":"a","completedAt":"b"}
                """));

        final var result = gateway.buildInventory(new KnowledgeInventoryBuildRequest(List.of(), List.of(), false));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.fileCount()).isEqualTo(3);
        assertThat(result.skippedBreakdown().total()).isEqualTo(2);
        assertThat(result.skippedBreakdown().byReason()).isEqualTo(Map.of("NOT_INCLUDED", 1, "TOO_LARGE", 1));
    }

    @Test
    void inventoryStatusProxyDefaultsMissingSkippedBreakdown() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(200, """
                {"status":"READY","lastBuildAt":"b","sourceCount":1,"fileCount":3,"skippedCount":2}
                """));

        final var result = gateway.inventoryStatus();

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.skippedBreakdown().total()).isEqualTo(2);
        assertThat(result.skippedBreakdown().byReason()).isEmpty();
    }

    @Test
    void inventoryFilesProxyMapsLineCountAndDecodePolicy() {
        final FakeHttpClient client = new FakeHttpClient(200, """
                {"files":[{"sourceId":"svc","sourcePath":"svc","relativePath":"src/App.java","extension":".java","sizeBytes":12,"contentHash":"hash","lastModified":"m","lineCount":2,"decodePolicy":"utf-8:replace"}],"limit":100,"offset":0,"total":1}
                """);
        final HttpKnowledgeGateway gateway = gateway(client);

        final var result = gateway.files(new KnowledgeFilesRequest("svc", null, null, null, null));

        assertThat(result.files()).hasSize(1);
        assertThat(result.files().getFirst().lineCount()).isEqualTo(2);
        assertThat(result.files().getFirst().decodePolicy()).isEqualTo("utf-8:replace");
        assertThat(client.lastRequest.uri().getPath()).isEqualTo("/api/v1/knowledge/inventory/files");
        assertThat(client.lastRequest.uri().getQuery()).isEqualTo("sourceId=svc");
    }

    @Test
    void analysisBuildProxyMapsSuccess() {
        final FakeHttpClient client = new FakeHttpClient(200, """
                {"jobId":"job-1","status":"QUEUED","message":"Knowledge analysis job queued"}
                """);
        final HttpKnowledgeGateway gateway = gateway(client);

        final var result = gateway.buildAnalysis(new KnowledgeAnalysisBuildRequest(List.of("svc"), List.of(), false, 5, 1));

        assertThat(result.jobId()).isEqualTo("job-1");
        assertThat(client.lastRequest.uri().getPath()).isEqualTo("/api/v1/knowledge/analysis/build");
    }

    @Test
    void analysisJobProxyMapsSuccess() {
        final FakeHttpClient client = new FakeHttpClient(200, """
                {"jobId":"job-1","status":"RUNNING","startedAt":"a","sourceCount":1,"fileCount":2,"processedFileCount":1,"failedFileCount":0,"currentSourceId":"svc","currentRelativePath":"A.java","sourceIds":["svc"],"lastProgressAt":"p","symbolCount":3,"relationCount":4,"diagnostics":[]}
                """);
        final HttpKnowledgeGateway gateway = gateway(client);

        final var result = gateway.analysisJob("job-1");

        assertThat(result.status()).isEqualTo("RUNNING");
        assertThat(result.sourceIds()).containsExactly("svc");
        assertThat(result.lastProgressAt()).isEqualTo("p");
        assertThat(client.lastRequest.uri().getPath()).isEqualTo("/api/v1/knowledge/analysis/jobs/job-1");
    }

    @Test
    void analysisStopProxyMapsSuccess() {
        final FakeHttpClient client = new FakeHttpClient(200, """
                {"jobId":"job-1","status":"STOP_REQUESTED","message":"Knowledge analysis stop requested"}
                """);
        final HttpKnowledgeGateway gateway = gateway(client);

        final var result = gateway.stopAnalysis("job-1");

        assertThat(result.status()).isEqualTo("STOP_REQUESTED");
        assertThat(client.lastRequest.method()).isEqualTo("POST");
        assertThat(client.lastRequest.uri().getPath()).isEqualTo("/api/v1/knowledge/analysis/jobs/job-1/stop");
    }

    @Test
    void analysisStatusProxyMapsSuccess() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(200, """
                {"status":"READY","latestJobId":"job-1","activeJob":null,"lastCompletedAt":"b","sourceCount":1,"fileCount":2,"symbolCount":3,"relationCount":4}
                """));

        final var result = gateway.analysisStatus();

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.symbolCount()).isEqualTo(3);
    }

    @Test
    void analysisFilesProxyMapsQueryParams() {
        final FakeHttpClient client = new FakeHttpClient(200, """
                {"files":[],"total":0,"limit":5,"offset":0}
                """);
        final HttpKnowledgeGateway gateway = gateway(client);

        gateway.analysisFiles(new KnowledgeAnalysisFilesRequest("svc", "ANALYZED", "A", 5, 0));

        assertThat(client.lastRequest.uri().getRawQuery()).contains("sourceId=svc", "status=ANALYZED", "pathContains=A");
    }

    @Test
    void analysisSymbolsProxyMapsQueryParams() {
        final FakeHttpClient client = new FakeHttpClient(200, """
                {"symbols":[],"total":0,"limit":5,"offset":0}
                """);
        final HttpKnowledgeGateway gateway = gateway(client);

        gateway.analysisSymbols(new KnowledgeAnalysisSymbolsRequest("svc", "HTTP_HANDLER", "CLASS", "A", "name", "CODE", "STATIC", 5, 0));

        assertThat(client.lastRequest.uri().getRawQuery()).contains("sourceId=svc", "role=HTTP_HANDLER", "kind=CLASS", "pathContains=A", "nameContains=name", "flowDomain=CODE", "factOrigin=STATIC");
    }

    @Test
    void analysisSymbolsMapsOptionalGraphFields() {
        final FakeHttpClient client = new FakeHttpClient(200, """
                {"symbols":[{"symbolId":"s1","sourceId":"svc","relativePath":"A.java","name":"findById","kind":"CALLABLE","roles":[],"lineStart":2,"lineEnd":4,"summary":"Finds by id.","metadata":{},"graphNodeId":"n1","stableKey":"stable-node","nodeKind":"CALLABLE","displayName":"TicketRepository.findById","qualifiedName":"TicketRepository.findById","responsibilitySummary":"Finds by id.","confidence":0.88,"factStatus":"TRUSTED","factOrigin":"LLM","flowDomain":"CODE","evidenceCount":1,"diagnosticCount":0}],"total":1,"limit":5,"offset":0}
                """);
        final HttpKnowledgeGateway gateway = gateway(client);

        final var result = gateway.analysisSymbols(new KnowledgeAnalysisSymbolsRequest("svc", null, null, null, null, 5, 0));

        assertThat(result.symbols()).hasSize(1);
        assertThat(result.symbols().getFirst().graphNodeId()).isEqualTo("n1");
        assertThat(result.symbols().getFirst().stableKey()).isEqualTo("stable-node");
        assertThat(result.symbols().getFirst().factOrigin()).isEqualTo("LLM");
        assertThat(result.symbols().getFirst().flowDomain()).isEqualTo("CODE");
        assertThat(result.symbols().getFirst().responsibilitySummary()).isEqualTo("Finds by id.");
        assertThat(result.symbols().getFirst().factStatus()).isEqualTo("TRUSTED");
        assertThat(result.symbols().getFirst().evidenceCount()).isEqualTo(1);
    }

    @Test
    void analysisRelationsProxyMapsQueryParams() {
        final FakeHttpClient client = new FakeHttpClient(200, """
                {"relations":[],"total":0,"limit":5,"offset":0}
                """);
        final HttpKnowledgeGateway gateway = gateway(client);

        gateway.analysisRelations(new KnowledgeAnalysisRelationsRequest("svc", "CALLS", "from", "to", "CODE", "LLM", 5, 0));

        assertThat(client.lastRequest.uri().getRawQuery()).contains("sourceId=svc", "relation=CALLS", "fromSymbolId=from", "toSymbolId=to", "flowDomain=CODE", "factOrigin=LLM");
    }

    @Test
    void analysisRelationsMapsOptionalGraphFields() {
        final FakeHttpClient client = new FakeHttpClient(200, """
                {"relations":[{"relationId":"r1","sourceId":"svc","fromSymbolId":"s1","toSymbolId":"s2","relation":"CALLS","confidence":0.91,"evidence":["line 4-4"],"lineStart":4,"lineEnd":4,"metadata":{},"graphEdgeId":"e1","fromGraphNodeId":"n1","toGraphNodeId":"n2","edgeType":"CALLS","resolutionStatus":"RESOLVED","factStatus":"TRUSTED","factOrigin":"LLM","flowDomain":"CODE","unresolvedTarget":null,"evidenceCount":1,"diagnosticCount":0}],"total":1,"limit":5,"offset":0}
                """);
        final HttpKnowledgeGateway gateway = gateway(client);

        final var result = gateway.analysisRelations(new KnowledgeAnalysisRelationsRequest("svc", "CALLS", null, null, 5, 0));

        assertThat(result.relations()).hasSize(1);
        assertThat(result.relations().getFirst().graphEdgeId()).isEqualTo("e1");
        assertThat(result.relations().getFirst().fromGraphNodeId()).isEqualTo("n1");
        assertThat(result.relations().getFirst().toGraphNodeId()).isEqualTo("n2");
        assertThat(result.relations().getFirst().edgeType()).isEqualTo("CALLS");
        assertThat(result.relations().getFirst().factOrigin()).isEqualTo("LLM");
        assertThat(result.relations().getFirst().flowDomain()).isEqualTo("CODE");
        assertThat(result.relations().getFirst().resolutionStatus()).isEqualTo("RESOLVED");
        assertThat(result.relations().getFirst().factStatus()).isEqualTo("TRUSTED");
        assertThat(result.relations().getFirst().evidenceCount()).isEqualTo(1);
    }

    @Test
    void connectionFailureMapsToUnavailable() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(new ConnectException("refused")));

        assertThatThrownBy(gateway::status)
                .isInstanceOfSatisfying(KnowledgeGatewayException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(KnowledgeGatewayErrorCode.KNOWLEDGE_UNAVAILABLE));
    }

    @Test
    void timeoutMapsToTimeout() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(new HttpTimeoutException("timeout")));

        assertThatThrownBy(gateway::status)
                .isInstanceOfSatisfying(KnowledgeGatewayException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(KnowledgeGatewayErrorCode.KNOWLEDGE_TIMEOUT));
    }

    @Test
    void invalidJsonMapsToBadResponse() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(200, "not-json"));

        assertThatThrownBy(gateway::status)
                .isInstanceOfSatisfying(KnowledgeGatewayException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE));
    }

    @Test
    void backendNotFoundPreservesControlledCode() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(404, """
                {"code":"ANALYSIS_JOB_NOT_FOUND","message":"Analysis job not found"}
                """));

        assertThatThrownBy(() -> gateway.analysisJob("missing-job"))
                .isInstanceOfSatisfying(KnowledgeGatewayException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(KnowledgeGatewayErrorCode.KNOWLEDGE_NOT_FOUND);
                    assertThat(exception.getResponseCode()).isEqualTo("ANALYSIS_JOB_NOT_FOUND");
                    assertThat(exception.getMessage()).isEqualTo("Analysis job not found");
                });
    }

    @Test
    void backendConflictMapsToConflict() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(409, """
                {"code":"ANALYSIS_JOB_ALREADY_RUNNING","message":"Knowledge analysis job already running"}
                """));

        assertThatThrownBy(() -> gateway.buildAnalysis(new KnowledgeAnalysisBuildRequest(List.of(), List.of(), false, null, 1)))
                .isInstanceOfSatisfying(KnowledgeGatewayException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(KnowledgeGatewayErrorCode.KNOWLEDGE_CONFLICT);
                    assertThat(exception.getResponseCode()).isEqualTo("ANALYSIS_JOB_ALREADY_RUNNING");
                });
    }

    @Test
    void backendBadRequestMapsToRequestFailed() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(400, """
                {"code":"ANALYSIS_BUILD_FAILED","message":"Analysis build failed"}
                """));

        assertThatThrownBy(() -> gateway.buildAnalysis(new KnowledgeAnalysisBuildRequest(List.of(), List.of(), false, null, 1)))
                .isInstanceOfSatisfying(KnowledgeGatewayException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(KnowledgeGatewayErrorCode.KNOWLEDGE_REQUEST_FAILED);
                    assertThat(exception.getResponseCode()).isEqualTo("ANALYSIS_BUILD_FAILED");
                });
    }

    @Test
    void nonLocalhostBaseUrlRejected() {
        final KnowledgeClientProperties properties = new KnowledgeClientProperties();
        properties.setBaseUrl(URI.create("http://example.com:7081"));
        final HttpKnowledgeGateway gateway = new HttpKnowledgeGateway(new ObjectMapper(), properties, new FakeHttpClient(200, "{}"), new KnowledgeHttpErrorMapper());

        assertThatThrownBy(gateway::status)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Knowledge base URL must point to localhost");
    }

    private static HttpKnowledgeGateway gateway(final HttpClient client) {
        final KnowledgeClientProperties properties = new KnowledgeClientProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:7081"));
        return new HttpKnowledgeGateway(new ObjectMapper(), properties, client, new KnowledgeHttpErrorMapper());
    }

    private static final class FakeHttpClient extends HttpClient {

        private final int status;
        private final String body;
        private final IOException failure;
        private int calls;
        private HttpRequest lastRequest;

        private FakeHttpClient(final int status, final String body) {
            this.status = status;
            this.body = body;
            this.failure = null;
        }

        private FakeHttpClient(final IOException failure) {
            this.status = 0;
            this.body = null;
            this.failure = failure;
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(2));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(final HttpRequest request,
                                        final HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            this.calls++;
            this.lastRequest = request;
            if (this.failure != null) {
                throw this.failure;
            }
            return new FakeHttpResponse<>(request, this.status, (T) this.body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                                final HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                                final HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                final HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private record FakeHttpResponse<T>(HttpRequest request, int statusCode, T body) implements HttpResponse<T> {

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (left, right) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return this.request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
