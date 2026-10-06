package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.convert.ApplicationConversionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class ForgeAgentCatalogTimeoutConfigurationTest {
    @Test
    void catalogUsesItsOwnBoundWhileConnectionReadsKeepOrdinaryAgentTimeout() {
        var transport=mock(HttpClient.class);
        var builder=mock(HttpClient.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(transport);
        when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request=invocation.getArgument(0);
                    Duration expected=request.uri().getPath().endsWith("/available")
                            ?Duration.ofSeconds(55):Duration.ofSeconds(30);
                    assertThat(request.timeout()).contains(expected);
                    return CompletableFuture.failedFuture(new HttpTimeoutException("synthetic timeout"));
                });
        try(var clients=mockStatic(HttpClient.class)) {
            clients.when(HttpClient::newBuilder).thenReturn(builder);
            new ApplicationContextRunner()
                    .withInitializer(context -> context.getBeanFactory().setConversionService(
                            ApplicationConversionService.getSharedInstance()))
                    .withUserConfiguration(ForgeAgentHttpClientConfiguration.class)
                    .withBean(RestClient.Builder.class, RestClient::builder)
                    .withBean(ForgeAgentClientCallExecutor.class, () -> mock(ForgeAgentClientCallExecutor.class))
                    .withPropertyValues("forge.ai.infrastructure.agent.base-url=http://127.0.0.1:7091",
                            "forge.ai.infrastructure.agent.connect-timeout=2s",
                            "forge.ai.infrastructure.agent.read-timeout=30s",
                            "forge.mcp.catalog.agent-read-timeout=55s")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var client=context.getBean(ForgeAgentHttpClient.class);
                        assertThatThrownBy(() -> client.listAvailableMcp("github", null, 20))
                                .isInstanceOf(ResourceAccessException.class);
                        assertThatThrownBy(client::listMcpConnections).isInstanceOf(ResourceAccessException.class);
                        assertThat(context.getBean(ForgeAgentClientProperties.class).getReadTimeout())
                                .isEqualTo(Duration.ofSeconds(30));
                    });
        }
    }

    @Test
    void pendingCatalogReadDoesNotPreventAnotherTypedAgentRead() throws Exception {
        var received=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try(var workers=Executors.newFixedThreadPool(2);
            var caller=Executors.newSingleThreadExecutor()) {
            server.setExecutor(workers);
            server.createContext("/api/v1/integrations/mcp/available",exchange -> {
                received.countDown();
                try {
                    if(!release.await(5,TimeUnit.SECONDS)) return;
                    byte[] body="{\"servers\":[],\"nextCursor\":null}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
                finally {exchange.close();}
            });
            server.createContext("/api/v1/integrations/mcp/connections",exchange -> {
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("[]".getBytes());exchange.close();
            });
            server.start();
            try {
                var properties=new ForgeAgentClientProperties();
                properties.setBaseUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
                properties.setConnectTimeout(Duration.ofSeconds(2));properties.setReadTimeout(Duration.ofMillis(500));
                var client=new ForgeAgentHttpClientConfiguration().forgeAgentHttpClient(
                        properties,RestClient.builder(),Duration.ofSeconds(3));
                var response=caller.submit(() -> client.listAvailableMcp("github",null,20));
                assertThat(received.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(client.listMcpConnections()).isEmpty();
                assertThatThrownBy(() -> response.get(750,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();assertThat(response.get(2,TimeUnit.SECONDS).servers()).isEmpty();
            } finally {release.countDown();server.stop(0);}
        }
    }
}
