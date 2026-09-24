package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.domain.exception.AgentClientException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

class ForgeAgentLogStreamingHttpClientTest {
  private HttpClient httpClient;
  private ForgeAgentLogStreamingHttpClient client;

  @BeforeEach
  void setUp() {
    this.httpClient = mock(HttpClient.class);
    final ForgeAgentClientProperties properties = new ForgeAgentClientProperties();
    properties.setBaseUrl(URI.create("http://forge-agent:8080"));
    this.client =
        new ForgeAgentLogStreamingHttpClient(
            this.httpClient, properties, new ForgeAgentClientCallExecutor(properties));
  }

  @Test
  void opensExpectedPathAndQueryBeforeReturningClosableStream() throws Exception {
    final UUID projectId = UUID.randomUUID();
    final UUID first = UUID.randomUUID();
    final UUID second = UUID.randomUUID();
    final AtomicReference<HttpRequest> captured = new AtomicReference<>();
    final ByteArrayInputStream input =
        new ByteArrayInputStream("event: log\n\n".getBytes(StandardCharsets.UTF_8));
    doAnswer(
            invocation -> {
              captured.set(invocation.getArgument(0));
              return response(200, input);
            })
        .when(this.httpClient)
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

    final var stream = this.client.open(projectId, List.of(first, second), 250);
    final ByteBuffer buffer = ByteBuffer.allocate(64);
    assertThat(stream.read(buffer)).isPositive();
    stream.close();

    assertThat(captured.get().uri().getPath())
        .isEqualTo("/api/v1/projects/" + projectId + "/logs/stream");
    assertThat(captured.get().uri().getQuery())
        .contains("sourceId=" + first, "sourceId=" + second, "lines=250");
  }

  @Test
  void enabledStreamSendsOnlyConfiguredServiceBearer() throws Exception {
    byte[] raw=new byte[32]; java.util.Arrays.fill(raw,(byte)5);
    var file=java.nio.file.Files.createTempFile("agent-service", ".token");
    try {
      java.nio.file.Files.writeString(file,java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
      java.nio.file.Files.setPosixFilePermissions(file,java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
              java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
      var properties=new ForgeAgentClientProperties(); properties.setBaseUrl(URI.create("http://127.0.0.1:7091"));
      var credential=new AgentServiceCredential(file,properties.getBaseUrl());
      client=new ForgeAgentLogStreamingHttpClient(httpClient,properties,new ForgeAgentClientCallExecutor(properties),credential);
      var captured=new AtomicReference<HttpRequest>();
      doAnswer(invocation -> { captured.set(invocation.getArgument(0)); return response(200,new ByteArrayInputStream(new byte[0])); })
              .when(httpClient).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
      client.open(UUID.randomUUID(),List.of(),10).close();
      assertThat(captured.get().headers().firstValue("Authorization")).contains(credential.authorization());
    } finally { java.nio.file.Files.deleteIfExists(file); }
  }

  @Test
  void mapsUpstreamErrorBeforeReturningAStream() throws Exception {
    final HttpResponse<java.io.InputStream> upstream =
        response(
            409,
            new ByteArrayInputStream(
                "{\"code\":\"TARGET_INVALID\",\"message\":\"missing\"}"
                    .getBytes(StandardCharsets.UTF_8)));
    when(this.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(upstream);

    assertThatThrownBy(() -> this.client.open(UUID.randomUUID(), List.of(UUID.randomUUID()), 100))
        .isInstanceOfSatisfying(
            AgentClientException.class,
            exception -> {
              assertThat(exception.statusCode()).isEqualTo(409);
              assertThat(exception.responseBody()).contains("TARGET_INVALID");
            });
  }

  @Test
  void mapsTransportFailureBeforeReturningAStream() throws Exception {
    when(this.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("connection refused"));

    assertThatThrownBy(() -> this.client.open(UUID.randomUUID(), List.of(UUID.randomUUID()), 100))
        .isInstanceOf(ResourceAccessException.class);
  }

  @Test
  void rejectsSuccessfulResponseWithoutEventStreamContentType() throws Exception {
    final java.io.InputStream input = mock(java.io.InputStream.class);
    final HttpResponse<java.io.InputStream> upstream =
        response(200, input, MediaType.APPLICATION_JSON_VALUE);
    when(this.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(upstream);

    assertThatThrownBy(() -> this.client.open(UUID.randomUUID(), List.of(UUID.randomUUID()), 100))
        .isInstanceOf(RestClientException.class)
        .hasMessageContaining("text/event-stream");
    verify(input).close();
  }

  @SuppressWarnings("unchecked")
  private static HttpResponse<java.io.InputStream> response(
      final int status, final java.io.InputStream body) {
    return response(status, body, MediaType.TEXT_EVENT_STREAM_VALUE);
  }

  @SuppressWarnings("unchecked")
  private static HttpResponse<java.io.InputStream> response(
      final int status, final java.io.InputStream body, final String contentType) {
    final HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    when(response.headers())
        .thenReturn(
            HttpHeaders.of(
                Map.of(org.springframework.http.HttpHeaders.CONTENT_TYPE, List.of(contentType)),
                (name, value) -> true));
    return response;
  }
}
