package com.sitionix.forgeai.infrastructure.agentclient.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentClientProperties;
import com.sitionix.forgeai.domain.remoteaccess.RemoteAccessClientException;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mapstruct.factory.Mappers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RemoteAccessHttpClientConfigurationTest {
    @TempDir Path directory;
    private ApplicationContextRunner context(String timeout, int port) throws Exception {
        Path secret = directory.resolve("service.secret");
        Files.writeString(secret, "a".repeat(43));
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
        var general = new ForgeAgentClientProperties();
        general.setBaseUrl(URI.create("http://127.0.0.1:" + port));
        general.setConnectTimeout(Duration.ofSeconds(2));
        general.setReadTimeout(Duration.ofSeconds(30));
        var runner = new ApplicationContextRunner().withUserConfiguration(RemoteAccessHttpClientConfiguration.class)
            .withBean(ForgeAgentClientProperties.class, () -> general)
            .withPropertyValues("forge.remote-access.enabled=true", "forge.remote-access.service-secret-file=" + secret);
        return timeout == null ? runner : runner.withPropertyValues("forge.remote-access.agent-read-timeout=" + timeout);
    }

    @ParameterizedTest
    @ValueSource(strings = {"30s", "89s", "99s", "0s", "-1s"})
    void insufficientTimeoutFailsConfiguration(String timeout) throws Exception {
        context(timeout, 7091).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("forge.remote-access.agent-read-timeout must be at least 100s");
        });
    }

    @Test void disabledFeatureDoesNotValidateTimeoutOrRequireCredentials() {
        new ApplicationContextRunner().withUserConfiguration(RemoteAccessHttpClientConfiguration.class)
            .withPropertyValues("forge.remote-access.enabled=false", "forge.remote-access.agent-read-timeout=1s")
            .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(RemoteAccessHttpClient.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"100s", "120s", "150s"})
    @NullSource
    void actualRequestUsesDedicatedBoundAndTimeoutMapsToSafeUnavailable(String timeout) throws Exception {
        var runner = context(timeout, 7091);
        Duration expected = timeout == null ? Duration.ofSeconds(120)
            : Duration.ofSeconds(Long.parseLong(timeout.substring(0, timeout.length() - 1)));
        var transport = mock(HttpClient.class);
        var builder = mock(HttpClient.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(transport);
        when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(invocation -> {
                HttpRequest request = invocation.getArgument(0);
                assertThat(request.timeout()).contains(expected);
                return CompletableFuture.failedFuture(new HttpTimeoutException("transport-detail-canary"));
            });
        try (var clients = mockStatic(HttpClient.class)) {
            clients.when(HttpClient::newBuilder).thenReturn(builder);
            runner.run(ctx -> {
                assertThat(ctx).hasNotFailed();
                var adapter = new RemoteAccessClientAdapter(ctx.getBean(RemoteAccessHttpClient.class),
                    Mappers.getMapper(RemoteAccessClientMapper.class));
                assertThatThrownBy(() -> adapter.revoke(UUID.randomUUID()))
                    .isInstanceOfSatisfying(RemoteAccessClientException.class, failure -> {
                        assertThat(failure.status()).isEqualTo(503);
                        assertThat(failure.code()).isEqualTo("REMOTE_ACCESS_UNAVAILABLE");
                    }).hasNoCause().hasMessage("Remote Access unavailable");
                assertThat(ctx.getBean(ForgeAgentClientProperties.class).getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
            });
        }
    }

    @Test void realRevokeCanFinishAfterOrdinaryThirtySecondTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/remote-access/sessions", exchange -> {
            try {
                Thread.sleep(31_000);
                byte[] body = "{\"status\":\"REVOKED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            context("120s", server.getAddress().getPort()).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                var response = ctx.getBean(RemoteAccessHttpClient.class).revoke(UUID.randomUUID());
                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getBody().status().name()).isEqualTo("REVOKED");
            });
        } finally { server.stop(0); }
    }
}
