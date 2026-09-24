package com.sitionix.forgeai.infrastructure.agentclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.exception.AgentClientException;
import com.sitionix.forgeai.domain.exception.McpAgentClientException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
public class ForgeAgentClientCallExecutor {

  private final ForgeAgentClientProperties properties;
  private final ObjectMapper errorMapper = new ObjectMapper()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  /** MCP is the only client port that requires a parsed, cause-free transport error. */
  public <T> T executeMcp(final Supplier<T> call) {
    try {
      return execute(call);
    } catch (final AgentClientException exception) {
      throw parseMcpError(exception.statusCode(), exception.responseBody());
    } catch (final ResourceAccessException exception) {
      throw new McpAgentClientException(503, "UPSTREAM_UNAVAILABLE",
          "Forge Agent service is unavailable.", null);
    } catch (final RestClientException exception) {
      throw invalidMcpResponse();
    } catch (final RuntimeException exception) {
      throw invalidMcpResponse();
    }
  }

  private McpAgentClientException parseMcpError(final int status, final String body) {
    if (status < 400 || status > 599 || body == null) return invalidMcpResponse();
    try {
      final JsonNode node = errorMapper.readTree(body);
      if (node == null || !node.isObject() || node.size() < 2 || node.size() > 3
          || !node.has("code") || !node.get("code").isTextual()
          || !node.has("message") || !node.get("message").isTextual()) {
        return invalidMcpResponse();
      }
      final var names = node.fieldNames();
      while (names.hasNext()) {
        final String name = names.next();
        if (!name.equals("code") && !name.equals("message") && !name.equals("correlationId")) {
          return invalidMcpResponse();
        }
      }
      final String code = node.get("code").textValue();
      final String message = node.get("message").textValue();
      final JsonNode correlation = node.get("correlationId");
      if (code.isBlank() || message.isBlank()
          || correlation != null && !correlation.isNull() && !correlation.isTextual()) {
        return invalidMcpResponse();
      }
      return new McpAgentClientException(status, code, message,
          correlation == null || correlation.isNull() ? null : correlation.textValue());
    } catch (final JsonProcessingException exception) {
      return invalidMcpResponse();
    }
  }

  private McpAgentClientException invalidMcpResponse() {
    return new McpAgentClientException(502, "UPSTREAM_INVALID_RESPONSE",
        "Forge Agent service returned an invalid response.", null);
  }

  public <T> T execute(final Supplier<T> call) {
    if (!this.properties.enabled()) {
      throw new ResourceAccessException("Forge Agent service is disabled");
    }
    try {
      return call.get();
    } catch (final RestClientResponseException exception) {
      throw new AgentClientException(
          exception.getStatusCode().value(),
          exception.getResponseBodyAsString(),
          this.toMap(exception.getResponseHeaders()),
          exception);
    }
  }

  AgentClientException upstreamError(
      final int statusCode,
      final String responseBody,
      final Map<String, List<String>> responseHeaders,
      final Throwable cause) {
    return new AgentClientException(statusCode, responseBody, responseHeaders, cause);
  }

  private Map<String, List<String>> toMap(final HttpHeaders headers) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    final Map<String, List<String>> result = new LinkedHashMap<>();
    headers.forEach(
        (name, values) -> result.put(name, values == null ? List.of() : List.copyOf(values)));
    return result;
  }
}
