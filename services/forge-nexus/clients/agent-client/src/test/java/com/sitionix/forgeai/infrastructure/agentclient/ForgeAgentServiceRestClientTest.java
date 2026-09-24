package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

class ForgeAgentServiceRestClientTest {
  @TempDir Path directory;

  @Test
  void typedRequestOverwritesCallerAuthorizationWithServiceCredential() throws Exception {
    final AtomicReference<String> authorization = new AtomicReference<>();
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/projects", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
      final byte[] response = "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      final byte[] raw = new byte[32];
      Arrays.fill(raw, (byte) 8);
      final Path file = directory.resolve("service");
      Files.writeString(file, Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
      Files.setPosixFilePermissions(file,
          Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
      final ForgeAgentClientProperties properties = new ForgeAgentClientProperties();
      properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
      properties.setConnectTimeout(java.time.Duration.ofSeconds(2));
      properties.setReadTimeout(java.time.Duration.ofSeconds(2));
      final AgentServiceCredential credential = new AgentServiceCredential(file, properties.getBaseUrl());
      final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
      factory.registerSingleton("agentServiceCredential", credential);
      final ForgeAgentHttpClient client = new ForgeAgentHttpClientConfiguration()
          .forgeAgentHttpClient(properties,
              RestClient.builder().defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer caller"),
              factory.getBeanProvider(AgentServiceCredential.class), true);

      assertThat(client.listProjects()).isEmpty();
      assertThat(authorization.get()).isEqualTo(credential.authorization());
    } finally {
      server.stop(0);
    }
  }
}
