package com.sitionix.forgeai.infrastructure.jarvisclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsSummaryView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandResultView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisExecutionView;
import com.sitionix.forgeai.domain.port.JarvisGateway;
import com.sitionix.forgeai.domain.model.jarvis.JarvisGatewayErrorCode;
import com.sitionix.forgeai.domain.exception.JarvisGatewayException;
import com.sitionix.forgeai.domain.model.jarvis.JarvisIntentView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisModelView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisRuntimeView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisStatusView;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HttpJarvisGateway implements JarvisGateway {

    private final ObjectMapper objectMapper;
    private final JarvisClientProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public HttpJarvisGateway(final ObjectMapper objectMapper, final JarvisClientProperties properties) {
        this(objectMapper, properties, HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build());
    }

    public HttpJarvisGateway(final ObjectMapper objectMapper,
                             final JarvisClientProperties properties,
                             final HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public JarvisStatusView status() {
        final JsonNode node = this.send("GET", "/api/v1/jarvis/status", null);
        final JsonNode model = node.path("model");
        final JsonNode ollama = node.path("ollama");
        final JsonNode actions = node.path("actions");
        return new JarvisStatusView(
                this.requiredText(node, "status"),
                this.optionalText(node, "host"),
                node.path("port").isInt() ? node.path("port").asInt() : null,
                new JarvisModelView(this.optionalText(model, "defaultModel")),
                new JarvisRuntimeView(this.optionalText(ollama, "baseUrl"), this.optionalText(ollama, "status")),
                new JarvisActionsSummaryView(actions.path("count").isInt() ? actions.path("count").asInt() : null)
        );
    }

    @Override
    public JarvisActionsView actions() {
        final JsonNode node = this.send("GET", "/api/v1/jarvis/actions", null);
        final JsonNode actionsNode = node.path("actions");
        if (!actionsNode.isArray()) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.JARVIS_BAD_RESPONSE, "Jarvis actions response is invalid");
        }
        final List<JarvisActionView> actions = new ArrayList<>();
        actionsNode.forEach(action -> actions.add(new JarvisActionView(
                this.requiredText(action, "action"),
                this.optionalText(action, "description"),
                this.stringList(action.path("targets"))
        )));
        return new JarvisActionsView(actions);
    }

    @Override
    public JarvisCommandResultView command(final JarvisCommandRequest command) {
        if (command == null || command.text() == null || command.text().isBlank()) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.INVALID_COMMAND, "Command text must not be empty");
        }
        final JsonNode node = this.send("POST", "/api/v1/jarvis/command", Map.of("text", command.text()));
        final JsonNode intent = node.path("intent");
        final JsonNode execution = node.path("execution");
        return new JarvisCommandResultView(
                this.requiredText(node, "input"),
                new JarvisIntentView(
                        this.requiredText(intent, "action"),
                        this.optionalText(intent, "target"),
                        this.objectMap(intent.path("arguments"))
                ),
                new JarvisExecutionView(
                        execution.path("executed").asBoolean(false),
                        this.requiredText(execution, "message"),
                        this.optionalText(execution, "output")
                )
        );
    }

    private JsonNode send(final String method, final String path, final Object body) {
        this.properties.validateBaseUrl();
        final HttpRequest.Builder builder = HttpRequest.newBuilder(this.resolve(path))
                // Uvicorn logs Java's default HTTP/2 upgrade probe as an invalid request on local POST calls.
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
            throw new JarvisGatewayException(JarvisGatewayErrorCode.JARVIS_TIMEOUT, "Jarvis request timed out", e);
        } catch (final ConnectException e) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.JARVIS_UNAVAILABLE, "Jarvis is unavailable", e);
        } catch (final IOException e) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.JARVIS_UNAVAILABLE, "Jarvis is unavailable", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JarvisGatewayException(JarvisGatewayErrorCode.JARVIS_UNAVAILABLE, "Jarvis request was interrupted", e);
        }
    }

    private JsonNode handle(final HttpResponse<String> response) {
        final JsonNode node = this.parse(response.body());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return node;
        }
        final JarvisGatewayErrorCode code = this.errorCode(node.path("code").asText(null), response.statusCode());
        final String message = node.path("message").asText(code.name());
        throw new JarvisGatewayException(code, message);
    }

    private JarvisGatewayErrorCode errorCode(final String code, final int status) {
        if ("INVALID_COMMAND".equals(code)) {
            return JarvisGatewayErrorCode.INVALID_COMMAND;
        }
        if ("OLLAMA_UNAVAILABLE".equals(code)) {
            return JarvisGatewayErrorCode.OLLAMA_UNAVAILABLE;
        }
        if ("INVALID_MODEL_RESPONSE".equals(code)) {
            return JarvisGatewayErrorCode.JARVIS_BAD_RESPONSE;
        }
        if ("UNSUPPORTED_ACTION".equals(code)) {
            return JarvisGatewayErrorCode.UNSUPPORTED_ACTION;
        }
        if ("ACTION_EXECUTION_FAILED".equals(code)) {
            return JarvisGatewayErrorCode.ACTION_EXECUTION_FAILED;
        }
        if (status == 403) {
            return JarvisGatewayErrorCode.UNSUPPORTED_ACTION;
        }
        if (status == 503) {
            return JarvisGatewayErrorCode.JARVIS_UNAVAILABLE;
        }
        return JarvisGatewayErrorCode.JARVIS_BAD_RESPONSE;
    }

    private JsonNode parse(final String body) {
        try {
            return this.objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (final JsonProcessingException e) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.JARVIS_BAD_RESPONSE, "Jarvis returned invalid JSON", e);
        }
    }

    private String serialize(final Object body) {
        try {
            return this.objectMapper.writeValueAsString(body);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Jarvis request", e);
        }
    }

    private URI resolve(final String path) {
        return this.properties.getBaseUrl().resolve(path);
    }

    private String requiredText(final JsonNode node, final String field) {
        final String value = this.optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.JARVIS_BAD_RESPONSE, "Jarvis response is missing field: " + field);
        }
        return value;
    }

    private String optionalText(final JsonNode node, final String field) {
        final JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private List<String> stringList(final JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        final List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private Map<String, Object> objectMap(final JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        return this.objectMapper.convertValue(node, LinkedHashMap.class);
    }
}
