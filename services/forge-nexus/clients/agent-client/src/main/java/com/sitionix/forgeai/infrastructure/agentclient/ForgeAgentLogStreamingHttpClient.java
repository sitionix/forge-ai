package com.sitionix.forgeai.infrastructure.agentclient;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
class ForgeAgentLogStreamingHttpClient {
  private final RestClient forgeAgentRestClient;

  ForgeAgentLogStreamingHttpClient(
      @Qualifier("forgeAgentLogStreamingRestClient") final RestClient restClient) {
    this.forgeAgentRestClient = restClient;
  }

  void stream(
      final UUID projectId,
      final List<UUID> sourceIds,
      final int lines,
      final OutputStream output) {
    forgeAgentRestClient
        .get()
        .uri(
            builder ->
                builder
                    .path("/api/v1/projects/{projectId}/logs/stream")
                    .queryParam("sourceId", sourceIds.toArray())
                    .queryParam("lines", lines)
                    .build(projectId))
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange(
            (request, response) -> {
              try {
                if (response.getStatusCode().isError()) {
                  final byte[] body = response.getBody().readAllBytes();
                  throw new RestClientResponseException(
                      "Forge Agent log stream failed",
                      response.getStatusCode().value(),
                      response.getStatusText(),
                      response.getHeaders(),
                      body,
                      StandardCharsets.UTF_8);
                }
                response.getBody().transferTo(output);
                output.flush();
                return null;
              } catch (final IOException exception) {
                throw new LogStreamTransferException(exception);
              }
            });
  }

  private static final class LogStreamTransferException extends RuntimeException {
    private LogStreamTransferException(final IOException cause) {
      super(cause);
    }
  }
}
