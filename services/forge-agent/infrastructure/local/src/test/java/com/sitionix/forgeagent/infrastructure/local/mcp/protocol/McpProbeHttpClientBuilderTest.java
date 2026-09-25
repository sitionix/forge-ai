package com.sitionix.forgeagent.infrastructure.local.mcp.protocol;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpProbeHttpClientBuilderTest {
    @Test void laterPostDoesNotEraseObservedForbiddenStatusBeforeItCompletes() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        var secondEntered = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (requests.incrementAndGet() == 2) {
                secondEntered.countDown();
                try {
                    if (!releaseSecond.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        try {
            var builder = new McpProbeHttpClientBuilder();
            var client = builder.build();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"))
                    .timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(McpProbeHttpClientBuilder.AuthStatus.class);
            assertThat(builder.authStatus()).isEqualTo(403);
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(builder.authStatus()).isEqualTo(403);
            releaseSecond.countDown();
            assertThatThrownBy(pending::join).hasCauseInstanceOf(McpProbeHttpClientBuilder.AuthStatus.class);
        } finally {
            releaseSecond.countDown();
            server.stop(0);
        }
    }
}
