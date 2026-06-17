package com.sitionix.forgeai.infrastructure.jarvisclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisChatRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisChatResponse;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandResultView;
import com.sitionix.forgeai.domain.port.JarvisGateway;
import com.sitionix.forgeai.domain.model.jarvis.JarvisGatewayErrorCode;
import com.sitionix.forgeai.domain.exception.JarvisGatewayException;
import com.sitionix.forgeai.domain.model.jarvis.JarvisStatusView;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
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
        final JarvisStatusView view = this.convert(this.send("GET", "/api/v1/jarvis/status", null), JarvisStatusView.class);
        this.required(view.status(), "status");
        return view;
    }

    @Override
    public JarvisActionsView actions() {
        final JarvisActionsView view = this.convert(this.send("GET", "/api/v1/jarvis/actions", null), JarvisActionsView.class);
        if (view.actions() == null) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.JARVIS_BAD_RESPONSE, "Jarvis actions response is invalid");
        }
        view.actions().forEach(action -> this.required(action.action(), "actions.action"));
        return view;
    }

    @Override
    public JarvisCommandResultView command(final JarvisCommandRequest command) {
        if (command == null || command.text() == null || command.text().isBlank()) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.INVALID_COMMAND, "Command text must not be empty");
        }
        final JarvisCommandResultView view = this.convert(this.send("POST", "/api/v1/jarvis/command", Map.of("text", command.text())), JarvisCommandResultView.class);
        this.required(view.input(), "input");
        if (view.intent() == null || view.execution() == null) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.JARVIS_BAD_RESPONSE, "Jarvis command response is invalid");
        }
        this.required(view.intent().action(), "intent.action");
        this.required(view.execution().message(), "execution.message");
        return view;
    }

    @Override
    public JarvisChatResponse chat(final JarvisChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.INVALID_COMMAND, "Chat message must not be empty");
        }
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", request.message());
        if (request.maxContextChars() != null) {
            body.put("maxContextChars", request.maxContextChars());
        }
        final JarvisChatResponse view = this.convert(this.send("POST", "/api/v1/jarvis/chat", body), JarvisChatResponse.class);
        this.required(view.answer(), "answer");
        if (view.usedContext() == null || view.diagnostics() == null) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.JARVIS_BAD_RESPONSE, "Jarvis chat response is invalid");
        }
        return view;
    }

    private String send(final String method, final String path, final Object body) {
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

    private String handle(final HttpResponse<String> response) {
        final String responseBody = response.body() == null || response.body().isBlank() ? "{}" : response.body();
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return responseBody;
        }
        final JarvisErrorResponse error = this.parseError(responseBody);
        final JarvisGatewayErrorCode code = this.errorCode(error == null ? null : error.code(), response.statusCode());
        final String message = this.firstText(error == null ? null : error.message(), code.name());
        throw new JarvisGatewayException(code, message);
    }

    private JarvisGatewayErrorCode errorCode(final String code, final int status) {
        if ("INVALID_COMMAND".equals(code)) {
            return JarvisGatewayErrorCode.INVALID_COMMAND;
        }
        if ("OLLAMA_UNAVAILABLE".equals(code)) {
            return JarvisGatewayErrorCode.OLLAMA_UNAVAILABLE;
        }
        if ("KNOWLEDGE_UNAVAILABLE".equals(code)) {
            return JarvisGatewayErrorCode.KNOWLEDGE_UNAVAILABLE;
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

    private JarvisErrorResponse parseError(final String body) {
        try {
            return this.objectMapper.readValue(body == null || body.isBlank() ? "{}" : body, JarvisErrorResponse.class);
        } catch (final JsonProcessingException e) {
            return null;
        }
    }

    private <T> T convert(final String body, final Class<T> type) {
        try {
            return this.objectMapper.readValue(body == null || body.isBlank() ? "{}" : body, type);
        } catch (final JsonProcessingException e) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.JARVIS_BAD_RESPONSE, "Jarvis response is invalid", e);
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

    private void required(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.JARVIS_BAD_RESPONSE, "Jarvis response is missing field: " + field);
        }
    }

    private String firstText(final String primary, final String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private record JarvisErrorResponse(String code, String message) {
    }
}
