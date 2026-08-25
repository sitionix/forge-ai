package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.domain.port.AgentLogStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

class ForgeAgentLogStreamingHttpClient {
  private final HttpClient httpClient;
  private final ForgeAgentClientProperties properties;
  private final ForgeAgentClientCallExecutor callExecutor;

  ForgeAgentLogStreamingHttpClient(
      final HttpClient httpClient,
      final ForgeAgentClientProperties properties,
      final ForgeAgentClientCallExecutor callExecutor) {
    this.httpClient = httpClient;
    this.properties = properties;
    this.callExecutor = callExecutor;
  }

  AgentLogStream open(final UUID projectId, final List<UUID> sourceIds, final int lines) {
    final URI uri =
        UriComponentsBuilder.fromUri(this.properties.getBaseUrl())
            .path("/api/v1/projects/{projectId}/logs/stream")
            .queryParam("sourceId", sourceIds.toArray())
            .queryParam("lines", lines)
            .buildAndExpand(projectId)
            .encode()
            .toUri();
    final HttpRequest request =
        HttpRequest.newBuilder(uri)
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .GET()
            .build();
    try {
      final HttpResponse<InputStream> response =
          this.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        try (InputStream body = response.body()) {
          throw this.callExecutor.upstreamError(
              response.statusCode(), readText(body), response.headers().map(), null);
        }
      }
      if (!isEventStream(response)) {
        close(response.body());
        throw new RestClientException(
            "Forge Agent log stream response is not text/event-stream");
      }
      return new InputAgentLogStream(response.body());
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ResourceAccessException(
          "Forge Agent log stream startup was interrupted", new IOException(exception));
    } catch (final IOException exception) {
      throw new ResourceAccessException("Forge Agent log stream is unavailable", exception);
    }
  }

  private static boolean isEventStream(final HttpResponse<?> response) {
    return response
        .headers()
        .firstValue(HttpHeaders.CONTENT_TYPE)
        .map(ForgeAgentLogStreamingHttpClient::isEventStream)
        .orElse(false);
  }

  private static boolean isEventStream(final String contentType) {
    try {
      return MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.TEXT_EVENT_STREAM);
    } catch (final IllegalArgumentException exception) {
      return false;
    }
  }

  private static void close(final InputStream body) {
    try {
      body.close();
    } catch (final IOException ignored) {
      // Invalid upstream responses must remain invalid-response failures.
    }
  }

  private static String readText(final InputStream body) throws IOException {
    final StringBuilder text = new StringBuilder();
    final char[] buffer = new char[1024];
    try (Reader reader = new InputStreamReader(body, StandardCharsets.UTF_8)) {
      int read;
      while ((read = reader.read(buffer)) >= 0) {
        text.append(buffer, 0, read);
      }
    }
    return text.toString();
  }

  private static final class InputAgentLogStream implements AgentLogStream {
    private final ReadableByteChannel channel;

    private InputAgentLogStream(final InputStream input) {
      this.channel = Channels.newChannel(input);
    }

    @Override
    public int read(final ByteBuffer buffer) {
      try {
        return this.channel.read(buffer);
      } catch (final IOException exception) {
        throw new ResourceAccessException("Forge Agent log stream failed", exception);
      }
    }

    @Override
    public void close() {
      try {
        this.channel.close();
      } catch (final IOException ignored) {
        // Closing is idempotent and disconnect cleanup must not mask the original outcome.
      }
    }
  }
}
