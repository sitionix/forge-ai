package com.sitionix.forgeai.infrastructure.knowledgeclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisBuildView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisJobView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisRelationsRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisRelationsView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisStopView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisSymbolsRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisSymbolsView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGateway;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayErrorCode;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayException;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServicesStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSkippedBreakdownView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSourcesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeViews;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "forge.ai.infrastructure.knowledge.mode", havingValue = "http", matchIfMissing = true)
public class HttpKnowledgeGateway implements KnowledgeGateway {

    private final ObjectMapper objectMapper;
    private final KnowledgeClientProperties properties;
    private final HttpClient httpClient;
    private final KnowledgeHttpErrorMapper errorMapper;

    @Autowired
    public HttpKnowledgeGateway(final ObjectMapper objectMapper, final KnowledgeClientProperties properties) {
        this(objectMapper, properties, HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build(), new KnowledgeHttpErrorMapper());
    }

    public HttpKnowledgeGateway(final ObjectMapper objectMapper,
                                final KnowledgeClientProperties properties,
                                final HttpClient httpClient,
                                final KnowledgeHttpErrorMapper errorMapper) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = httpClient;
        this.errorMapper = errorMapper;
    }

    @Override
    public KnowledgeStatusView status() {
        return this.normalize(this.convert(this.send("GET", "/api/v1/knowledge/status", null), KnowledgeStatusView.class));
    }

    @Override
    public KnowledgeSourcesView sources() {
        return this.convert(this.send("GET", "/api/v1/knowledge/sources", null), KnowledgeSourcesView.class);
    }

    @Override
    public KnowledgeServicesStatusView servicesStatus() {
        return this.servicesStatus(null);
    }

    @Override
    public KnowledgeServicesStatusView servicesStatus(final String detailsSourceId) {
        final StringBuilder query = new StringBuilder();
        append(query, "detailsSourceId", detailsSourceId);
        return this.convert(this.send("GET", "/api/v1/knowledge/services/status" + query, null), KnowledgeServicesStatusView.class);
    }

    @Override
    public KnowledgeInventoryBuildResultView buildInventory(final KnowledgeInventoryBuildRequest request) {
        return this.normalize(this.convert(this.send("POST", "/api/v1/knowledge/inventory/build", normalizeBuildRequest(request)), KnowledgeInventoryBuildResultView.class));
    }

    @Override
    public KnowledgeInventoryStatusView inventoryStatus() {
        return this.normalize(this.convert(this.send("GET", "/api/v1/knowledge/inventory/status", null), KnowledgeInventoryStatusView.class));
    }

    @Override
    public KnowledgeFilesView files(final KnowledgeFilesRequest request) {
        return this.convert(this.send("GET", "/api/v1/knowledge/inventory/files" + query(request), null), KnowledgeFilesView.class);
    }

    @Override
    public KnowledgeAnalysisBuildView buildAnalysis(final KnowledgeAnalysisBuildRequest request) {
        return this.convert(this.send("POST", "/api/v1/knowledge/analysis/build", normalizeAnalysisBuildRequest(request)), KnowledgeAnalysisBuildView.class);
    }

    @Override
    public KnowledgeAnalysisJobView analysisJob(final String jobId) {
        return this.convert(this.send("GET", "/api/v1/knowledge/analysis/jobs/" + encode(jobId), null), KnowledgeAnalysisJobView.class);
    }

    @Override
    public KnowledgeAnalysisStopView stopAnalysis(final String jobId) {
        return this.convert(this.send("POST", "/api/v1/knowledge/analysis/jobs/" + encode(jobId) + "/stop", Map.of()), KnowledgeAnalysisStopView.class);
    }

    @Override
    public KnowledgeAnalysisStatusView analysisStatus() {
        return this.convert(this.send("GET", "/api/v1/knowledge/analysis/status", null), KnowledgeAnalysisStatusView.class);
    }

    @Override
    public KnowledgeAnalysisFilesView analysisFiles(final KnowledgeAnalysisFilesRequest request) {
        return this.convert(this.send("GET", "/api/v1/knowledge/analysis/files" + query(request), null), KnowledgeAnalysisFilesView.class);
    }

    @Override
    public KnowledgeAnalysisSymbolsView analysisSymbols(final KnowledgeAnalysisSymbolsRequest request) {
        return this.convert(this.send("GET", "/api/v1/knowledge/analysis/symbols" + query(request), null), KnowledgeAnalysisSymbolsView.class);
    }

    @Override
    public KnowledgeAnalysisRelationsView analysisRelations(final KnowledgeAnalysisRelationsRequest request) {
        return this.convert(this.send("GET", "/api/v1/knowledge/analysis/relations" + query(request), null), KnowledgeAnalysisRelationsView.class);
    }

    private String send(final String method, final String path, final Object body) {
        this.properties.validateBaseUrl();
        final HttpRequest.Builder builder = HttpRequest.newBuilder(this.properties.getBaseUrl().resolve(path))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(this.properties.getReadTimeout())
                .header("Accept", "application/json");
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(this.serialize(body)));
        } else {
            builder.GET();
        }
        try {
            final HttpResponse<String> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return this.handle(response);
        } catch (final HttpTimeoutException e) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_TIMEOUT, "Knowledge request timed out", e);
        } catch (final ConnectException e) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_UNAVAILABLE, "Knowledge is unavailable", e);
        } catch (final IOException e) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_UNAVAILABLE, "Knowledge is unavailable", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_UNAVAILABLE, "Knowledge request was interrupted", e);
        }
    }

    private String handle(final HttpResponse<String> response) {
        final String responseBody = response.body() == null || response.body().isBlank() ? "{}" : response.body();
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return responseBody;
        }
        final KnowledgeBackendErrorResponse error = this.parseError(responseBody);
        final KnowledgeGatewayErrorCode code = this.errorMapper.map(response.statusCode());
        final String responseCode = this.firstText(error == null ? null : error.code(), code.name());
        final String message = this.firstText(error == null ? null : error.message(), error == null ? null : error.detail(), responseCode);
        throw new KnowledgeGatewayException(code, responseCode, message);
    }

    private KnowledgeBackendErrorResponse parseError(final String body) {
        try {
            return this.objectMapper.readValue(body == null || body.isBlank() ? "{}" : body, KnowledgeBackendErrorResponse.class);
        } catch (final JsonProcessingException e) {
            return null;
        }
    }

    private <T> T convert(final String body, final Class<T> type) {
        try {
            return this.objectMapper.readValue(body == null || body.isBlank() ? "{}" : body, type);
        } catch (final JsonProcessingException e) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE, "Knowledge response is invalid", e);
        }
    }

    private String firstText(final String primary, final String fallback) {
        return this.firstText(primary, fallback, fallback);
    }

    private String firstText(final String primary, final String secondary, final String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary;
        }
        return fallback;
    }

    private record KnowledgeBackendErrorResponse(String code, String message, String detail) {
    }

    private String serialize(final Object body) {
        try {
            return this.objectMapper.writeValueAsString(body);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Knowledge request", e);
        }
    }

    private KnowledgeInventoryBuildRequest normalizeBuildRequest(final KnowledgeInventoryBuildRequest request) {
        return new KnowledgeInventoryBuildRequest(
                request == null || request.sourceIds() == null ? List.of() : request.sourceIds(),
                request == null || request.groups() == null ? List.of() : request.groups(),
                request != null && Boolean.TRUE.equals(request.force())
        );
    }

    private KnowledgeStatusView normalize(final KnowledgeStatusView view) {
        if (view == null || view.inventory() == null) {
            return view;
        }
        final KnowledgeViews.KnowledgeInventorySummaryView inventory = view.inventory();
        return new KnowledgeStatusView(
                view.status(),
                view.module(),
                view.catalog(),
                new KnowledgeViews.KnowledgeInventorySummaryView(
                        inventory.implemented(),
                        inventory.status(),
                        inventory.lastBuildAt(),
                        inventory.sourceCount(),
                        inventory.fileCount(),
                        inventory.skippedCount(),
                        this.normalize(inventory.skippedBreakdown(), inventory.skippedCount())
                ),
                view.inventoryRefresh(),
                view.coverage(),
                view.freshness(),
                view.message()
        );
    }

    private KnowledgeInventoryBuildResultView normalize(final KnowledgeInventoryBuildResultView view) {
        if (view == null) {
            return null;
        }
        return new KnowledgeInventoryBuildResultView(
                view.status(),
                view.sourceCount(),
                view.fileCount(),
                view.skippedCount(),
                this.normalize(view.skippedBreakdown(), view.skippedCount()),
                view.startedAt(),
                view.completedAt()
        );
    }

    private KnowledgeInventoryStatusView normalize(final KnowledgeInventoryStatusView view) {
        if (view == null) {
            return null;
        }
        return new KnowledgeInventoryStatusView(
                view.status(),
                view.lastBuildAt(),
                view.sourceCount(),
                view.fileCount(),
                view.skippedCount(),
                this.normalize(view.skippedBreakdown(), view.skippedCount())
        );
    }

    private KnowledgeSkippedBreakdownView normalize(final KnowledgeSkippedBreakdownView breakdown,
                                                    final Integer skippedCount) {
        if (breakdown == null) {
            return new KnowledgeSkippedBreakdownView(skippedCount == null ? 0 : skippedCount, Map.of());
        }
        return new KnowledgeSkippedBreakdownView(
                breakdown.total() == null ? skippedCount == null ? 0 : skippedCount : breakdown.total(),
                breakdown.byReason() == null ? Map.of() : breakdown.byReason()
        );
    }

    private KnowledgeAnalysisBuildRequest normalizeAnalysisBuildRequest(final KnowledgeAnalysisBuildRequest request) {
        return new KnowledgeAnalysisBuildRequest(
                request == null || request.sourceIds() == null ? List.of() : request.sourceIds(),
                request == null || request.groups() == null ? List.of() : request.groups(),
                request != null && Boolean.TRUE.equals(request.force()),
                request == null ? null : request.maxFiles(),
                request == null || request.concurrency() == null ? 1 : request.concurrency()
        );
    }

    private String query(final KnowledgeFilesRequest request) {
        if (request == null) {
            return "";
        }
        final StringBuilder query = new StringBuilder();
        append(query, "sourceId", request.sourceId());
        append(query, "pathContains", request.pathContains());
        append(query, "extension", request.extension());
        append(query, "limit", request.limit());
        append(query, "offset", request.offset());
        return query.toString();
    }

    private String query(final KnowledgeAnalysisFilesRequest request) {
        if (request == null) {
            return "";
        }
        final StringBuilder query = new StringBuilder();
        append(query, "sourceId", request.sourceId());
        append(query, "status", request.status());
        append(query, "pathContains", request.pathContains());
        append(query, "limit", request.limit());
        append(query, "offset", request.offset());
        return query.toString();
    }

    private String query(final KnowledgeAnalysisSymbolsRequest request) {
        if (request == null) {
            return "";
        }
        final StringBuilder query = new StringBuilder();
        append(query, "sourceId", request.sourceId());
        append(query, "role", request.role());
        append(query, "kind", request.kind());
        append(query, "pathContains", request.pathContains());
        append(query, "nameContains", request.nameContains());
        append(query, "flowDomain", request.flowDomain());
        append(query, "factOrigin", request.factOrigin());
        append(query, "limit", request.limit());
        append(query, "offset", request.offset());
        return query.toString();
    }

    private String query(final KnowledgeAnalysisRelationsRequest request) {
        if (request == null) {
            return "";
        }
        final StringBuilder query = new StringBuilder();
        append(query, "sourceId", request.sourceId());
        append(query, "relation", request.relation());
        append(query, "fromSymbolId", request.fromSymbolId());
        append(query, "toSymbolId", request.toSymbolId());
        append(query, "flowDomain", request.flowDomain());
        append(query, "factOrigin", request.factOrigin());
        append(query, "limit", request.limit());
        append(query, "offset", request.offset());
        return query.toString();
    }

    private String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void append(final StringBuilder query, final String key, final Object value) {
        if (value == null || value.toString().isBlank()) {
            return;
        }
        query.append(query.isEmpty() ? "?" : "&")
                .append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                .append("=")
                .append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
    }
}
