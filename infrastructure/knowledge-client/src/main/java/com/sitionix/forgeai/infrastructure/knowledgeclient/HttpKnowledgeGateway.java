package com.sitionix.forgeai.infrastructure.knowledgeclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGateway;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayErrorCode;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayException;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSearchRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSearchResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSourcesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeStatusView;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
        return this.convert(this.send("GET", "/api/v1/knowledge/status", null), KnowledgeStatusView.class);
    }

    @Override
    public KnowledgeSourcesView sources() {
        return this.convert(this.send("GET", "/api/v1/knowledge/sources", null), KnowledgeSourcesView.class);
    }

    @Override
    public KnowledgeInventoryBuildResultView buildInventory(final KnowledgeInventoryBuildRequest request) {
        return this.convert(this.send("POST", "/api/v1/knowledge/inventory/build", normalizeBuildRequest(request)), KnowledgeInventoryBuildResultView.class);
    }

    @Override
    public KnowledgeInventoryStatusView inventoryStatus() {
        return this.convert(this.send("GET", "/api/v1/knowledge/inventory/status", null), KnowledgeInventoryStatusView.class);
    }

    @Override
    public KnowledgeFilesView files(final KnowledgeFilesRequest request) {
        return this.convert(this.send("GET", "/api/v1/knowledge/inventory/files" + query(request), null), KnowledgeFilesView.class);
    }

    @Override
    public KnowledgeSearchResultView search(final KnowledgeSearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.SEARCH_QUERY_INVALID, "Search query must not be empty");
        }
        return this.convert(this.send("POST", "/api/v1/knowledge/search", normalizeSearchRequest(request)), KnowledgeSearchResultView.class);
    }

    @Override
    public KnowledgeContextView context(final KnowledgeContextRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.CONTEXT_QUERY_INVALID, "Context query must not be empty");
        }
        return this.convert(this.send("POST", "/api/v1/knowledge/context", normalizeContextRequest(request)), KnowledgeContextView.class);
    }

    private JsonNode send(final String method, final String path, final Object body) {
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

    private JsonNode handle(final HttpResponse<String> response) {
        final JsonNode node = this.parse(response.body());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return node;
        }
        final KnowledgeGatewayErrorCode code = this.errorMapper.map(node, response.statusCode());
        throw new KnowledgeGatewayException(code, node.path("message").asText(code.name()));
    }

    private JsonNode parse(final String body) {
        try {
            return this.objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (final JsonProcessingException e) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE, "Knowledge returned invalid JSON", e);
        }
    }

    private <T> T convert(final JsonNode node, final Class<T> type) {
        try {
            return this.objectMapper.treeToValue(node, type);
        } catch (final JsonProcessingException e) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE, "Knowledge response is invalid", e);
        }
    }

    private String serialize(final Object body) {
        try {
            return this.objectMapper.writeValueAsString(body == null ? Map.of() : body);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Knowledge request", e);
        }
    }

    private Map<String, Object> normalizeBuildRequest(final KnowledgeInventoryBuildRequest request) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceIds", request == null || request.sourceIds() == null ? java.util.List.of() : request.sourceIds());
        body.put("groups", request == null || request.groups() == null ? java.util.List.of() : request.groups());
        body.put("force", request != null && Boolean.TRUE.equals(request.force()));
        return body;
    }

    private Map<String, Object> normalizeSearchRequest(final KnowledgeSearchRequest request) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", request.query());
        body.put("sourceIds", request.sourceIds() == null ? java.util.List.of() : request.sourceIds());
        body.put("groups", request.groups() == null ? java.util.List.of() : request.groups());
        body.put("limit", request.limit() == null ? 20 : request.limit());
        return body;
    }

    private Map<String, Object> normalizeContextRequest(final KnowledgeContextRequest request) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", request.query());
        body.put("sourceIds", request.sourceIds() == null ? java.util.List.of() : request.sourceIds());
        body.put("groups", request.groups() == null ? java.util.List.of() : request.groups());
        body.put("maxChars", request.maxChars() == null ? 12000 : request.maxChars());
        body.put("maxItems", request.maxItems() == null ? 12 : request.maxItems());
        body.put("includeContent", request.includeContent() == null || request.includeContent());
        return body;
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
